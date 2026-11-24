#!/usr/bin/env bb
;; Ontology classifier / duplicate collapser for the Gmail ontology pipeline.
;;
;; Ported from the Python antigravity-gmail-project pipeline.  Unlike the
;; upstream script, gmail-takeout uses DeepSeek (OpenAI-compatible chat
;; completions), never Gemini.
;;
;; The classifier is deliberately boring and strict:
;;   1. read config + raw candidates + ontology cache
;;   2. diff candidates against the cache (exact and lowercase keys)
;;   3. locally enforce the ontology noise rules (allow/stop/DF/generic/URL)
;;   4. send only unseen terms to DeepSeek, in batches
;;   5. validate and repair the model output against the five-category schema
;;   6. enforce canonical aliases, stable normalization and one category per
;;      normalized term
;;   7. write the cache atomically with both exact and lowercase keys
;;   8. append an audit trail and an optional JSON validation report
;;
;; No per-email LLM calls ever happen here: the cache is the source of truth
;; for the local matchers.
;;
;; Usage:
;;   export DEEPSEEK_API_KEY='...'
;;   bb scripts/ontology_classifier.clj                 # uses ./ontology by default
;;
;;   bb scripts/ontology_classifier.clj --check      # validate config/cache/candidates
;;   bb scripts/ontology_classifier.clj --dry-run    # show the diff, no API/writes
;;   bb scripts/ontology_classifier.clj --help
;;
;; See doc/ontology-classifier.md for the full contract.

(ns ontology-classifier
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def default-categories
  ["companies" "roles" "fields" "job_types" "locations"])

(def default-model "deepseek-v4-flash")
(def default-base-url "https://api.deepseek.com")
(def default-df-threshold 0.25)
(def default-batch-size 100)
(def default-max-retries 2)
(def default-delay-ms 1000)
(def default-retry-backoff-ms 1500)
(def default-temperature 0.1)
(def default-max-tokens 8000)
(def default-timeout-ms 120000)

(def ^:private short-acronyms
  "Short terms the upstream compiler explicitly allowed through the length
   filter.  Kept identical for parity with the Python pipeline."
  #{"AI" "ML" "UI" "UX" "IP"})

(def ^:private category-synonyms
  {"company" "companies"
   "companies" "companies"
   "company_name" "companies"
   "role" "roles"
   "roles" "roles"
   "field" "fields"
   "fields" "fields"
   "job_type" "job_types"
   "job_type_name" "job_types"
   "job_types" "job_types"
   "jobtypes" "job_types"
   "job-types" "job_types"
   "location" "locations"
   "locations" "locations"})

;; ---------------------------------------------------------------------------
;; Small utilities
;; ---------------------------------------------------------------------------

(defn- warn [& xs]
  (binding [*out* *err*]
    (apply println xs)))

(defn- expand-path [p]
  (str (fs/expand-home (str p))))

(defn- now-iso []
  (str (Instant/now)))

(defn- blank? [x]
  (str/blank? (str x)))

(defn- normalize-category-name [c]
  (-> (str c) str/trim str/lower-case (str/replace #"[\s\-]+" "_")))

(defn- clean-normalized
  "Normalize whitespace and reject blank / control-character / oversized
   normalized terms.  Returns nil when the value cannot be used as a key."
  [s]
  (when (string? s)
    (let [s (-> s str/trim (str/replace #"\s+" " "))]
      (when (and (seq s)
                 (<= (count s) 120)
                 (not (re-find #"[\u0000-\u001f\u007f]" s)))
        s))))

(defn- normalization-key
  "Stable near-duplicate key: case, whitespace, hyphen, underscore, dot,
   apostrophe and slash variants collapse together.  Symbols such as + and #
   are preserved so C++ / C# / C stay distinct."
  [s]
  (when (string? s)
    (-> s str/trim str/lower-case (str/replace #"[\s\-_\.'’/]+" ""))))

(defn- normalize-category [categories c]
  (when-not (blank? c)
    (let [c (normalize-category-name c)
          c (get category-synonyms c c)]
      (when (contains? (set categories) c) c))))

(defn- preferred-normalized
  "Pick a deterministic canonical spelling for a group of normalized values.
   Preference: contains a space, contains a hyphen, longer, more frequent,
   earlier.  This turns LiquidAI -> Liquid AI and HttpKit -> HTTP-Kit."
  [normalizeds]
  (let [normalizeds (vec (remove blank? normalizeds))
        freq       (frequencies normalizeds)
        first-idx  (reduce-kv (fn [m i n]
                                (if (contains? m n) m (assoc m n i)))
                              {}
                              (zipmap (range) normalizeds))
        score      (fn [n] [(if (str/includes? n " ") 1 0)
                            (if (str/includes? n "-") 1 0)
                            (count n)
                            (get freq n 0)
                            (- (get first-idx n 0))])]
    (when (seq normalizeds)
      (->> (distinct normalizeds)
           (sort-by score (fn [a b] (compare b a)))
           first))))

(defn- preferred-category [categories]
  (let [categories (vec (remove nil? categories))
        freq       (frequencies categories)
        first-idx  (reduce-kv (fn [m i c]
                                (if (contains? m c) m (assoc m c i)))
                              {}
                              (zipmap (range) categories))]
    (when (seq categories)
      (->> (distinct categories)
           (sort-by (fn [c] [(get freq c 0) (- (get first-idx c 0))])
                    (fn [a b] (compare b a)))
           first))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(def cli-spec
  {:base-dir        {:alias :b :desc "Data root (default: $GMAILS_BASE_DIR, ./ontology, antigravity project, ~/code/gmails, cwd)"}
   :config          {:desc "config.json (default: <base>/config.json)"}
   :candidates      {:desc "raw candidates JSON/EDN (default: <base>/raw_candidates.json)"}
   :cache           {:desc "ontology cache JSON/EDN (default: <base>/ontology_cache.json)"}
   :audit-log       {:desc "append-only JSONL audit log (default: <base>/ontology_audit.jsonl)"}
   :report          {:desc "write a JSON/EDN validation report to this path"}
   :model           {:alias :m :desc "DeepSeek model (default: $DEEPSEEK_MODEL or deepseek-v4-flash)"}
   :base-url        {:desc "OpenAI-compatible base URL (default: $DEEPSEEK_BASE or https://api.deepseek.com)"}
   :secrets-file    {:desc "EDN secrets file (default: ~/.secrets.edn)"}
   :batch-size      {:alias :n :coerce :long :desc "Terms per API request (default: 100)"}
   :limit           {:alias :l :coerce :long :desc "Max new terms to send this run (default: all)"}
   :max-retries     {:coerce :long :desc "Retries for transport and missing-term repair (default: 2)"}
   :delay           {:coerce :long :desc "Delay between API batches in ms (default: 1000)"}
   :timeout         {:coerce :long :desc "HTTP timeout in ms (default: 120000)"}
   :temperature     {:coerce :double :desc "Sampling temperature (default: 0.1)"}
   :max-tokens      {:coerce :long :desc "Completion token budget (default: 8000)"}
   :retry-backoff   {:coerce :long :desc "Base retry backoff in ms (default: 1500)"}
   :force           {:desc "Reclassify terms already present in the cache"}
   :normalize-cache {:desc "Also canonicalize existing cache values (one-time migration)"}
   :dry-run         {:desc "Show the plan and diff; no API calls and no writes"}
   :check           {:desc "Validate config/cache/candidates and report; no API calls"}
   :no-json-mode    {:desc "Do not send response_format=json_object (for some local models)"}
   :no-audit        {:desc "Do not append to the audit log"}
   :report-file     {:desc "Alias for --report"}
   :verbose         {:desc "Print stack traces and extra detail"}
   :help            {:alias :h :desc "Show help"}})

(defn- print-usage []
  (println "Ontology classifier (DeepSeek) — Gmail ontology pipeline, step 3")
  (println)
  (println "Usage:")
  (println "  bb scripts/ontology_classifier.clj [options]")
  (println)
  (println "Options:")
  (doseq [[k {:keys [alias desc]}] (sort-by (comp str key) cli-spec)]
    (let [flag (str "--" (name k))
          alias (when alias (str "-" (name alias) ", "))]
      (println (format "  %-22s %s" (str alias flag) (or desc "")))))
  (println)
  (println "Environment:")
  (println "  DEEPSEEK_API_KEY    API key (or :deepseek-api-key in ~/.secrets.edn)")
  (println "  DEEPSEEK_BASE       Base URL (or :deepseek-base in ~/.secrets.edn)")
  (println "  DEEPSEEK_MODEL      Model id (overrides config/default)")
  (println "  GMAILS_BASE_DIR     Data root when --base-dir is omitted")
  (println "  ~/.secrets.edn      :deepseek-api-key, :deepseek-base, :deepseek-model")
  (println)
  (println "Examples:")
  (println "  bb scripts/ontology_classifier.clj --check")
  (println "  bb scripts/ontology_classifier.clj --dry-run")
  (println "  bb scripts/ontology_classifier.clj                 # ./ontology by default")
  (println "  DEEPSEEK_BASE=http://localhost:11434/v1 DEEPSEEK_MODEL=qwen2.5:14b \\")
  (println "    bb scripts/ontology_classifier.clj --dry-run")
  (println)
  (println "Exit codes: 0 success, 1 validation/API/classification failure, 2 usage error."))

;; ---------------------------------------------------------------------------
;; Loading and paths
;; ---------------------------------------------------------------------------

(defn- script-ontology-dir
  "Return <repo>/ontology when the script is run from a source checkout."
  []
  (try
    (when-let [file (or *file* (System/getProperty "babashka.file"))]
      (when-not (blank? file)
        (let [root (-> (str file) fs/absolutize fs/parent fs/parent)]
          (str (fs/path root "ontology")))))
    (catch Exception _ nil)))

(defn- resolve-base-dir [{:keys [base-dir]}]
  (let [explicit (when-not (blank? base-dir) (expand-path base-dir))
        env      (System/getenv "GMAILS_BASE_DIR")
        cwd      (System/getProperty "user.dir")
        candidates [(when-not (blank? env) env)
                    (script-ontology-dir)
                    (str (fs/path cwd "ontology"))
                    "~/code/antigravity-gmail-project"
                    "~/code/gmails"]]
    (cond
      explicit
      (if (fs/exists? explicit)
        explicit
        (throw (ex-info (str "Base directory does not exist: " explicit)
                        {:base-dir explicit})))

      (not (blank? env))
      (let [f (expand-path env)]
        (if (fs/exists? f)
          f
          (throw (ex-info (str "GMAILS_BASE_DIR does not exist: " f)
                          {:base-dir f}))))

      :else
      (or (some (fn [p]
                  (when-not (blank? p)
                    (let [f (expand-path p)]
                      (when (fs/exists? f) f))))
                candidates)
          cwd))))

(defn- option-path [opts k base default-name]
  (if-let [p (get opts k)]
    (expand-path p)
    (str (fs/path base default-name))))

(defn- read-raw
  "Read a data file without altering object keys.  JSON is read with string
   keys so cache/candidate keys such as \"AAPL\" survive; EDN is read as-is."
  [path]
  (let [text (slurp path)]
    (try
      (if (str/ends-with? (str/lower-case (str path)) ".edn")
        (edn/read-string text)
        (json/parse-string text false))
      (catch Exception e
        (throw (ex-info (str "Could not parse data file: " path)
                        {:path path :error (.getMessage e)} e))))))

(defn- key->keyword [k]
  (if (keyword? k) k (keyword (str k))))

(defn- keywordize-keys [m]
  (if (map? m)
    (reduce-kv (fn [acc k v] (assoc acc (key->keyword k) v)) {} m)
    m))

(defn- cache-key->str [k]
  (if (keyword? k) (name k) (str k)))

(defn- normalize-cache [data]
  (when-not (map? data)
    (throw (ex-info "Ontology cache must contain a JSON/EDN object" {})))
  (into {}
        (map (fn [[k v]]
               [(cache-key->str k)
                (if (map? v) (keywordize-keys v) v)]))
        data))

(defn- normalize-config-raw [data]
  (let [m (if (map? data) data {})
        m (reduce-kv (fn [acc k v] (assoc acc (key->keyword k) v)) {} m)
        aliases (:canonical_aliases m)]
    (assoc m :canonical_aliases
           (if (map? aliases)
             (reduce-kv (fn [acc k v]
                          (assoc acc
                                 (if (keyword? k) (name k) (str k))
                                 (if (map? v) (keywordize-keys v) v)))
                        {}
                        aliases)
             aliases))))

(defn- normalize-candidate [c]
  (if (map? c)
    (reduce-kv (fn [acc k v] (assoc acc (key->keyword k) v)) {} c)
    c))

(defn- read-config-file [path]
  (if (fs/exists? path)
    (normalize-config-raw (read-raw path))
    {}))

(defn- read-cache-file [path]
  (when-not (fs/exists? path)
    (throw (ex-info (str "Ontology cache not found: " path) {:path path})))
  (normalize-cache (read-raw path)))

(defn- load-candidates [path]
  (when-not (fs/exists? path)
    (throw (ex-info (str "Raw candidates not found: " path
                         ". Run the candidate compiler first.")
                    {:path path})))
  (let [data (read-raw path)
        cs   (cond
               (vector? data) data
               (sequential? data) (vec data)
               (and (map? data) (vector? (or (:candidates data) (get data "candidates"))))
               (or (:candidates data) (get data "candidates"))
               (and (map? data) (vector? (or (:raw_candidates data) (get data "raw_candidates"))))
               (or (:raw_candidates data) (get data "raw_candidates"))
               :else (throw (ex-info (str "Candidates must be a JSON array: " path)
                                     {:path path})))]
    (mapv normalize-candidate cs)))

(defn- normalize-config [raw]
  (let [raw (if (map? raw) raw {})]
    (assoc raw
           :df_threshold
           (let [v (or (:df_threshold raw) (:df-threshold raw) default-df-threshold)]
             (cond
               (number? v) (double v)
               (string? v) (try (Double/parseDouble v) (catch Exception _ default-df-threshold))
               :else default-df-threshold))
           :user_allow_list (vec (or (:user_allow_list raw) (:user-allow-list raw) []))
           :user_stop_list (vec (or (:user_stop_list raw) (:user-stop-list raw) []))
           :standalone_generic (vec (or (:standalone_generic raw) (:standalone-generic raw) []))
           :auto_detected_df_stops (vec (or (:auto_detected_df_stops raw) (:auto-detected-df-stops raw) []))
           :canonical_aliases (or (:canonical_aliases raw) (:canonical-aliases raw) {})
           :categories (or (:categories raw) default-categories)
           :schema_version (or (:schema_version raw) (:schema-version raw) 1))))

(defn- config-categories [config]
  (let [raw (:categories config)]
    (when-not (and (sequential? raw) (seq raw))
      (throw (ex-info "config :categories must be a non-empty array" {:categories raw})))
    (let [cs (->> raw
                  (map #(normalize-category-name %))
                  (remove blank?)
                  distinct
                  vec)]
      (when (empty? cs)
        (throw (ex-info "config :categories must contain at least one category" {})))
      cs)))

(defn- alias-index [raw-aliases categories]
  (when-not (map? raw-aliases)
    (throw (ex-info "config :canonical_aliases must be a JSON/EDN object" {})))
  (reduce-kv
   (fn [m k v]
     (let [term (if (keyword? k) (name k) (str k))]
       (when (blank? term)
         (throw (ex-info "canonical_aliases contains a blank key" {})))
       (let [{:keys [normalized category]}
             (cond
               (string? v) {:normalized v}
               (vector? v) {:normalized (first v) :category (second v)}
               (map? v) {:normalized (or (:normalized v) (:canonical v) (:term v))
                         :category (:category v)}
               :else (throw (ex-info (str "canonical_aliases entry must be a string, pair or object: " term)
                                     {:term term})))]
         (when (blank? normalized)
           (throw (ex-info (str "canonical_aliases entry is missing :normalized: " term)
                           {:term term})))
         (let [n (clean-normalized (str normalized))
               c (when-not (blank? category) (normalize-category categories category))]
           (when-not n
             (throw (ex-info (str "canonical_aliases entry has an invalid normalized value: " term)
                             {:term term :normalized normalized})))
           (when (and (not (blank? category)) (nil? c))
             (throw (ex-info (str "canonical_aliases entry has an invalid category: " term)
                             {:term term :category category :allowed categories})))
           (assoc m (str/lower-case term) {:normalized n :category c})))))
   {}
   raw-aliases))

(defn- build-context [opts]
  (let [base-dir (resolve-base-dir opts)
        paths    {:base-dir   base-dir
                  :config     (option-path opts :config base-dir "config.json")
                  :candidates (option-path opts :candidates base-dir "raw_candidates.json")
                  :cache      (option-path opts :cache base-dir "ontology_cache.json")
                  :audit      (option-path opts :audit-log base-dir "ontology_audit.jsonl")
                  :report     (or (:report opts) (:report-file opts))}
        raw-config (read-config-file (:config paths))
        config     (normalize-config raw-config)
        _          (when-not (<= 0.0 (double (:df_threshold config)) 1.0)
                     (throw (ex-info "config :df_threshold must be between 0.0 and 1.0"
                                     {:df_threshold (:df_threshold config)})))
        categories (config-categories config)
        aliases    (alias-index (:canonical_aliases config) categories)
        cache      (if (fs/exists? (:cache paths))
                     (read-cache-file (:cache paths))
                     {})
        candidates (load-candidates (:candidates paths))]
    {:opts       opts
     :base-dir   base-dir
     :paths      paths
     :config     config
     :categories categories
     :alias-set  (set categories)
     :aliases    aliases
     :cache      cache
     :candidates candidates}))

;; ---------------------------------------------------------------------------
;; Credential resolution
;; ---------------------------------------------------------------------------

(defn- read-secrets [path]
  (let [f (fs/file (expand-path path))]
    (if (fs/exists? f)
      (try
        (let [data (edn/read-string (slurp f))]
          (if (map? data) data {}))
        (catch Exception e
          (warn "Warning: could not read secrets file" (str f) "-" (.getMessage e))
          {}))
      {})))

(defn- effective-config [ctx]
  (let [c (:config ctx)
        o (:opts ctx)]
    {:batch-size       (max 1 (long (or (:batch-size o) (:batch_size c) default-batch-size)))
     :max-retries      (max 0 (long (or (:max-retries o) (:max_retries c) default-max-retries)))
     :delay-ms         (max 0 (long (or (:delay o) (:delay_ms c) default-delay-ms)))
     :retry-backoff-ms (max 0 (long (or (:retry-backoff o) (:retry_backoff_ms c) default-retry-backoff-ms)))
     :temperature      (double (or (:temperature o) (:temperature c) default-temperature))
     :max-tokens       (max 1 (long (or (:max-tokens o) (:max_tokens c) default-max-tokens)))
     :timeout-ms       (max 1000 (long (or (:timeout o) (:timeout_ms c) default-timeout-ms)))
     :json-mode        (if (:no-json-mode o)
                         false
                         (get c :json_mode true))
     :schema-version   (:schema_version c)}))

(defn- resolve-credentials [ctx eff]
  (let [c           (:config ctx)
        opts        (:opts ctx)
        secrets     (read-secrets (or (:secrets-file opts) "~/.secrets.edn"))
        api-key     (or (let [v (System/getenv "DEEPSEEK_API_KEY")]
                          (when-not (blank? v) v))
                        (:deepseek-api-key secrets))
        base-url    (or (let [v (:base-url opts)] (when-not (blank? v) v))
                        (let [v (System/getenv "DEEPSEEK_BASE")] (when-not (blank? v) v))
                        (let [v (System/getenv "DEEPSEEK_BASE_URL")] (when-not (blank? v) v))
                        (let [v (or (:deepseek-base secrets)
                                    (:deepseek-base-url secrets))]
                          (when-not (blank? v) v))
                        (let [v (or (:base_url c) (:base-url c))] (when-not (blank? v) v))
                        default-base-url)
        model       (or (let [v (:model opts)] (when-not (blank? v) v))
                        (let [v (System/getenv "DEEPSEEK_MODEL")] (when-not (blank? v) v))
                        (let [v (or (:deepseek-model secrets)
                                    (:deepseek-model-name secrets))]
                          (when-not (blank? v) v))
                        (let [v (:model c)] (when-not (blank? v) v))
                        default-model)]
    {:api-key  (when-not (blank? api-key) (str api-key))
     :base-url (str base-url)
     :model    (str model)
     :eff      eff}))

(defn- build-client [ctx need-api?]
  (let [eff    (effective-config ctx)
        creds  (resolve-credentials ctx eff)
        client (merge creds
                      eff
                      {:categories (:categories ctx)
                       :aliases    (:aliases ctx)})]
    (when (and need-api? (blank? (:api-key client)))
      (throw (ex-info (str "DeepSeek API key is not configured. Set DEEPSEEK_API_KEY "
                           "or add :deepseek-api-key to ~/.secrets.edn.")
                      {:provider "deepseek"})))
    client))

;; ---------------------------------------------------------------------------
;; Candidate planning: cache diff + local noise enforcement
;; ---------------------------------------------------------------------------

(defn- candidate-term [c]
  (cond
    (string? c) c
    (map? c) (some-> (or (:term c) (:raw_term c) (:raw-term c)
                         (:value c) (:text c) (:candidate c))
                     str)
    :else (some-> c str)))

(defn- candidate-df [c]
  (when (map? c)
    (let [v (or (:df c) (:document_frequency c) (:document-frequency c))]
      (when (number? v) (double v)))))

(defn- filter-sets [config]
  {:allow    (set (map #(str/lower-case (str %)) (:user_allow_list config)))
   :stops    (set (map #(str/lower-case (str %)) (:user_stop_list config)))
   :generics (set (map #(str/lower-case (str %)) (:standalone_generic config)))
   :df-stops (set (map #(str/lower-case (str %)) (:auto_detected_df_stops config)))})

(defn- noise-reason
  "Return a keyword reason when a candidate is boilerplate/noise, nil when it
   should be classified.  The allow-list always wins, exactly as upstream."
  [sets config term df]
  (let [lc (str/lower-case term)]
    (cond
      (contains? (:allow sets) lc) nil
      (contains? (:stops sets) lc) :user-stop-list
      (contains? (:generics sets) lc) :standalone-generic
      (contains? (:df-stops sets) lc) :df-stop
      (and df (>= (double df) (:df_threshold config))) :df-threshold
      (re-matches #"^[0-9_\-\.\&\s]+$" term) :numeric
      (> (count term) 40) :too-long
      (re-find #"^[A-Z][A-Za-z0-9+#&'’]*[.!?]\s+\S" term) :sentence-fragment
      (some #(str/includes? term %) ["=" "%" "?" "/" "\\" "_" "|" ";" ":" "$" "*" "#" "@"])
      :url-fragment
      (and (< (count term) 3) (not (contains? short-acronyms term))) :too-short
      :else nil)))

(defn- cached-term? [cache term]
  (or (contains? cache term)
      (contains? cache (str/lower-case term))))

(defn- alias-for [aliases term]
  (get aliases (str/lower-case term)))

(defn- plan-run
  "Build the run plan.  Returns raw/unique/cached/filtered/pre-resolved/pending
   term collections plus the reason breakdown."
  [ctx]
  (let [raw     (:candidates ctx)
        config  (:config ctx)
        sets    (filter-sets config)
        cache   (:cache ctx)
        force?  (boolean (:force (:opts ctx)))
        initial {:raw-count   (count raw)
                 :unique      []
                 :cached      []
                 :filtered    []
                 :pre-resolved []
                 :pending     []
                 :seen        #{}}
        plan    (reduce
                 (fn [p c]
                   (let [term (candidate-term c)
                         df   (candidate-df c)]
                     (if (blank? term)
                       p
                       (let [lc (str/lower-case term)]
                         (if (contains? (:seen p) lc)
                           p
                           (let [p (-> p
                                       (update :seen conj lc)
                                       (update :unique conj term))]
                             (cond
                               (and (not force?) (cached-term? cache term))
                               (update p :cached conj term)

                               :else
                               (if-let [reason (noise-reason sets config term df)]
                                 (update p :filtered conj {:term term :reason reason})
                                 (if-let [alias (alias-for (:aliases ctx) term)]
                                   (if (:category alias)
                                     (update p :pre-resolved conj
                                             {:raw-term         term
                                              :normalized       (:normalized alias)
                                              :category         (:category alias)
                                              :source           :canonical
                                              :confidence       nil
                                              :alias-normalized (:normalized alias)
                                              :alias-category   (:category alias)})
                                     (update p :pending conj term))
                                   (update p :pending conj term))))))))))
                 initial
                 raw)]
    (assoc plan
           :pending-total (count (:pending plan))
           :pending (if-let [n (:limit (:opts ctx))]
                      (if (pos? n) (vec (take n (:pending plan))) (:pending plan))
                      (:pending plan))
           :filtered-breakdown (frequencies (map :reason (:filtered plan))))))

;; ---------------------------------------------------------------------------
;; Prompt
;; ---------------------------------------------------------------------------

(defn- system-prompt [categories]
  (str "You are an expert NLP and ontology engineer for a Gmail knowledge graph. "
       "Classify and normalize raw proper-noun terms extracted from email bodies.\n\n"
       "Return ONLY one JSON object with this shape:\n"
       "{\"classifications\":[{\"raw_term\":\"...\",\"normalized_term\":\"...\",\"category\":\"...\",\"confidence\":0.0}]}\n\n"
       "Rules:\n"
       "- Output exactly one entry per input term, in the same order. Do not add, omit or rename input terms.\n"
       "- raw_term must be the input term verbatim.\n"
       "- normalized_term must be a clean canonical spelling: collapse typos, spacing, hyphen, symbol and case variants "
       "(LiquidAI and Liquid-AI -> Liquid AI; HttpKit -> HTTP-Kit; dotnet -> Dotnet; google -> Google).\n"
       "- Abbreviations become their canonical form (AAPL -> Apple; ML -> Machine Learning).\n"
       "- category must be exactly one of: " (str/join ", " categories) ".\n"
       "- companies = brands, organizations, platforms, employers; roles = job titles/positions; "
       "fields = technologies, frameworks, libraries, skills, fields of study, concepts; "
       "job_types = work classifications (Full-time, Contract); locations = geographies or Remote.\n"
       "- The same normalized term must always receive the same category. Reuse an existing canonical spelling when one is provided.\n"
       "- Canonical mappings supplied by the user are authoritative: use their normalized_term and category exactly.\n"
       "- normalized_term must be <= 120 characters, contain no URLs, no control characters, and no surrounding punctuation.\n"
       "- confidence is optional, between 0 and 1; use a lower value when the category is ambiguous.\n"
       "- If unsure, choose the closest category rather than inventing a new one. Never wrap the JSON in markdown fences."))

(defn- user-prompt [terms aliases]
  (str "Classify these raw terms. Return JSON only.\n\n"
       "Input terms (JSON array):\n"
       (json/generate-string (vec terms) {:pretty true})
       "\n\n"
       (when (seq aliases)
         (str "Authoritative canonical mappings (JSON object; normalized_term/category must match):\n"
              (json/generate-string aliases {:pretty true})
              "\n\n"))
       "Remember: one entry per input term, raw_term verbatim, category one of the allowed values."))

(defn- build-messages [client terms]
  (let [alias-map (into {}
                        (keep (fn [t]
                                (when-let [a (alias-for (:aliases client) t)]
                                  [t (cond-> {:normalized (:normalized a)}
                                       (:category a) (assoc :category (:category a)))])))
                        terms)]
    [{:role "system" :content (system-prompt (:categories client))}
     {:role "user" :content (user-prompt terms alias-map)}]))

;; ---------------------------------------------------------------------------
;; DeepSeek client
;; ---------------------------------------------------------------------------

(defn- completions-url [base]
  (let [base (str/replace (str base) #"/+$" "")]
    (cond
      (str/ends-with? base "/chat/completions") base
      (str/ends-with? base "/v1") (str base "/chat/completions")
      :else (str base "/chat/completions"))))

(defn- post-chat [client messages json-mode?]
  (let [body (cond-> {:model       (:model client)
                      :messages    messages
                      :temperature (:temperature client)
                      :max_tokens  (:max-tokens client)
                      :stream      false}
               json-mode? (assoc :response_format {:type "json_object"}))
        url  (completions-url (:base-url client))]
    (try
      (http/post url
                 {:headers {"Authorization" (str "Bearer " (:api-key client))
                            "Content-Type"  "application/json"
                            "Accept"        "application/json"
                            "User-Agent"    "gmail-takeout-ontology-classifier"}
                  :body    (json/generate-string body)
                  :throw   false
                  :timeout (:timeout-ms client)})
      (catch Exception e
        {:status 0 :body (str "network error: " (.getMessage e)) :error e}))))

(defn- json-mode-unsupported? [body]
  (let [b (str/lower-case (str body))]
    (or (str/includes? b "response_format")
        (str/includes? b "json_object")
        (str/includes? b "json mode"))))

(defn- retryable-status? [status]
  (or (zero? status)
      (= 408 status)
      (= 409 status)
      (= 425 status)
      (= 429 status)
      (>= status 500)))

(defn- api-body-preview [body]
  (let [s (str body)]
    (subs s 0 (min 1200 (count s)))))

(defn- call-deepseek!
  "Call DeepSeek with retry/backoff.  Returns {:content ... :finish-reason ...}
   or throws ex-info on a non-retryable / exhausted error."
  [client messages]
  (loop [attempt 0
         json-mode? (:json-mode client)]
    (let [resp (post-chat client messages json-mode?)
          status (:status resp)]
      (cond
        (= 200 status)
        (let [data (try (json/parse-string (:body resp) true)
                        (catch Exception e
                          (throw (ex-info "DeepSeek returned a non-JSON HTTP body"
                                          {:status status
                                           :body (api-body-preview (:body resp))}
                                          e))))
              content (get-in data [:choices 0 :message :content])
              finish  (get-in data [:choices 0 :finish_reason])]
          (when (blank? content)
            (throw (ex-info "DeepSeek returned an empty message content"
                            {:status status
                             :body (api-body-preview (:body resp))})))
          {:content content :finish-reason finish})

        (and (= 400 status)
             json-mode?
             (json-mode-unsupported? (:body resp)))
        (do
          (warn "  Model rejected response_format=json_object; retrying without it.")
          (recur attempt false))

        (and (retryable-status? status)
             (< attempt (:max-retries client)))
        (let [wait-ms (+ (:retry-backoff-ms client)
                         (rand-int (max 1 (:retry-backoff-ms client))))]
          (warn (format "  DeepSeek HTTP %s on attempt %d/%d; retrying in %dms"
                        status (inc attempt) (inc (:max-retries client)) wait-ms))
          (Thread/sleep wait-ms)
          (recur (inc attempt) json-mode?))

        :else
        (throw (ex-info (str "DeepSeek API request failed (HTTP " status ")")
                        {:status status
                         :body (api-body-preview (:body resp))}))))))

;; ---------------------------------------------------------------------------
;; Response validation
;; ---------------------------------------------------------------------------

(defn- strip-code-fences [s]
  (-> s
      str/trim
      (str/replace #"(?s)^```(?:json)?\s*" "")
      (str/replace #"(?s)\s*```$" "")))

(defn- parse-llm-json [content]
  (let [text (strip-code-fences (str content))]
    (try
      (json/parse-string text true)
      (catch Exception first-error
        ;; Local models occasionally put prose around the JSON object.
        (let [start (str/index-of text "{")
              end   (str/last-index-of text "}")]
          (if (and start end (< start end))
            (try
              (json/parse-string (subs text start (inc end)) true)
              (catch Exception e
                (throw (ex-info "DeepSeek returned invalid JSON"
                                {:content (subs text 0 (min 800 (count text)))}
                                e))))
            (throw (ex-info "DeepSeek returned invalid JSON"
                            {:content (subs text 0 (min 800 (count text)))}
                            first-error))))))))

(defn- parse-confidence [v]
  (let [n (cond
            (number? v) (double v)
            (string? v) (try (Double/parseDouble v) (catch Exception _ nil))
            :else nil)]
    (when (and n (not (Double/isNaN n)) (not (Double/isInfinite n))
               (<= 0.0 n) (<= n 1.0))
      n)))

(defn- item-field [m & ks]
  (some (fn [k]
          (let [v (get m k)]
            (when (some? v) v)))
        ks))

(defn- validate-batch
  "Validate one model response against the requested terms.  Returns
   {:accepted [...] :rejected [...] :missing [...]}.  :missing terms are
   retryable; unknown extra terms are rejected but do not remove requested
   terms from the retry set."
  [client requested content]
  (let [data  (parse-llm-json content)
        items (cond
                (vector? data) data
                (sequential? data) (vec data)
                (sequential? (:classifications data)) (:classifications data)
                :else (throw (ex-info "Response is missing the :classifications array"
                                      {:content (subs (str content) 0 (min 800 (count (str content))))})))
        lower->term (into {} (map (fn [t] [(str/lower-case t) t]) requested))
        requested-set (set requested)
        initial {:accepted [] :rejected [] :seen #{}}
        result
        (reduce
         (fn [acc item]
           (if-not (map? item)
             (update acc :rejected conj {:raw_term (pr-str item) :reason :invalid-item})
             (let [raw        (some-> (item-field item :raw_term :raw-term :term) str str/trim)
                   normalized (clean-normalized (some-> (item-field item :normalized_term :normalized-term :normalized) str))
                   alias      (when-not (blank? raw) (alias-for (:aliases client) raw))
                   reported-cat (item-field item :category :category_name)
                   category0  (normalize-category (:categories client) reported-cat)
                   matched    (cond
                                (contains? requested-set raw) raw
                                (and (not (blank? raw))
                                     (contains? lower->term (str/lower-case raw)))
                                (get lower->term (str/lower-case raw))
                                :else nil)
                   final-normalized (or (:normalized alias) normalized)
                   final-category   (or (:category alias) category0)]
               (cond
                 (nil? matched)
                 (update acc :rejected conj {:raw_term raw :reason :unknown-term})

                 (contains? (:seen acc) matched)
                 (update acc :rejected conj {:raw_term matched :reason :duplicate})

                 (nil? final-normalized)
                 (update acc :rejected conj {:raw_term matched :reason :blank-normalized})

                 (nil? final-category)
                 (update acc :rejected conj {:raw_term matched
                                             :reason :invalid-category
                                             :reported reported-cat})

                 :else
                 (let [confidence (parse-confidence (item-field item :confidence :score))]
                   (-> acc
                       (update :seen conj matched)
                       (update :accepted conj
                               {:raw-term        matched
                                :normalized      final-normalized
                                :category        final-category
                                :confidence      confidence
                                :source          (if (:category alias) :canonical :llm)
                                :llm-normalized  normalized
                                :llm-category    category0
                                :alias-normalized (:normalized alias)
                                :alias-category   (:category alias)})))))))
         initial
         items)]
    (assoc result :missing (vec (remove (:seen result) requested)))))

;; ---------------------------------------------------------------------------
;; Consistency and canonicalization
;; ---------------------------------------------------------------------------

(defn- existing-groups [cache categories]
  (let [acc (reduce-kv
             (fn [acc _ v]
               (if (map? v)
                 (let [n (clean-normalized (:normalized v))
                       c (normalize-category categories (:category v))]
                   (if (and n c)
                     (let [k (normalization-key n)]
                       (-> acc
                           (update-in [:by-key k :normalizeds] (fnil conj []) n)
                           (update-in [:by-key k :categories] (fnil conj []) c)))
                     (update acc :invalid conj {:value v :reason :invalid-cache-entry})))
                 (update acc :invalid conj {:value v :reason :invalid-cache-value})))
             {:by-key {} :invalid []}
             cache)
        canonical (reduce-kv
                   (fn [m k g]
                     (assoc m k {:normalized (preferred-normalized (:normalizeds g))
                                 :category   (preferred-category (:categories g))
                                 :variants   (vec (distinct (:normalizeds g)))
                                 :categories (vec (distinct (:categories g)))}))
                   {}
                   (:by-key acc))]
    (assoc acc :canonical canonical)))

(defn- enforce-consistency
  "Enforce one normalized spelling and one category per normalization-key.
   Precedence: explicit canonical alias > existing cache > model output.
   Returns {:entries ... :conflicts [...] :collapsed [...] :existing ...}."
  [cache accepted categories]
  (let [{:keys [canonical invalid]} (existing-groups cache categories)
        groups (group-by #(normalization-key (:normalized %)) accepted)
        {entries :entries conflicts :conflicts collapsed :collapsed}
        (reduce-kv
         (fn [{:keys [entries conflicts collapsed]} k items]
           (let [existing       (get canonical k)
                 alias-norms    (vec (distinct (keep :alias-normalized items)))
                 alias-cats     (vec (distinct (keep :alias-category items)))
                 canonical-norm (or (first alias-norms)
                                    (:normalized existing)
                                    (preferred-normalized (map :normalized items)))
                 canonical-cat  (or (first alias-cats)
                                    (:category existing)
                                    (preferred-category (map :category items)))
                 llm-cats       (distinct (map :category items))
                 category-conflict? (or (> (count llm-cats) 1)
                                        (and existing (not= (:category existing) canonical-cat)))
                 conflict (when category-conflict?
                            {:normalization-key k
                             :normalized        (vec (distinct (map :normalized items)))
                             :categories        (vec llm-cats)
                             :existing-category (:category existing)
                             :chosen            canonical-cat})
                 collapsed? (or (> (count (set (map :normalized items))) 1)
                                (and existing (> (count (:variants existing)) 1)))
                 collapse (when collapsed?
                            {:normalization-key k
                             :variants          (vec (distinct (concat (:variants existing [])
                                                                      (map :normalized items))))
                             :chosen            canonical-norm})
                 new-entries
                 (mapv
                  (fn [item]
                    (assoc item
                           :normalized canonical-norm
                           :category canonical-cat
                           :normalized-overridden? (not= (:normalized item) canonical-norm)
                           :category-overridden? (not= (:category item) canonical-cat)
                           :consistency-source (cond
                                                 (seq alias-cats) :canonical
                                                 existing         :cache
                                                 :else            (or (:source item) :llm))))
                  items)]
             {:entries   (into entries new-entries)
              :conflicts (cond-> conflicts conflict (conj conflict))
              :collapsed (cond-> collapsed collapse (conj collapse))}))
         {:entries [] :conflicts [] :collapsed []}
         groups)]
    {:entries entries
     :conflicts conflicts
     :collapsed collapsed
     :overrides (vec (filter #(or (:normalized-overridden? %)
                                  (:category-overridden? %))
                             entries))
     :existing invalid}))

(defn- normalize-existing-cache [cache categories canonical]
  (reduce-kv
   (fn [acc k v]
     (if (map? v)
       (let [n (clean-normalized (:normalized v))
             c (normalize-category categories (:category v))
             g (when n (get canonical (normalization-key n)))]
         (if (and g c)
           (assoc acc k {:category (:category g) :normalized (:normalized g)})
           (assoc acc k v)))
       (assoc acc k v)))
   {}
   cache))

(defn- merge-entries [cache entries]
  (reduce
   (fn [c {:keys [raw-term normalized category]}]
     (if (or (blank? raw-term) (blank? normalized) (blank? category))
       c
       (let [v {:category category :normalized normalized}]
         (assoc c raw-term v (str/lower-case raw-term) v))))
   cache
   entries))

(defn- diff-cache [before after]
  (let [added   (count (remove #(contains? before %) (keys after)))
        removed (count (remove #(contains? after %) (keys before)))
        updated (count (filter (fn [k]
                                 (and (contains? before k)
                                      (not= (get before k) (get after k))))
                               (keys after)))]
    {:added added :updated updated :removed removed
     :before (count before) :after (count after)}))

;; ---------------------------------------------------------------------------
;; Cache validation
;; ---------------------------------------------------------------------------

(defn- validate-cache [cache categories]
  (let [{:keys [by-key invalid]} (existing-groups cache categories)
        groups (map (fn [[k g]]
                      (let [cats (distinct (:categories g))
                            norms (distinct (:normalizeds g))]
                        {:key k :categories (vec cats) :normalized (vec norms)}))
                    by-key)
        conflicts (filter #(> (count (:categories %)) 1) groups)
        variants  (filter #(> (count (:normalized %)) 1) groups)
        missing-lower (for [k (keys cache)
                            :when (and (not= k (str/lower-case k))
                                       (not (contains? cache (str/lower-case k))))]
                        k)
        category-counts (frequencies
                         (keep (fn [[_ v]]
                                 (when (map? v)
                                   (normalize-category categories (:category v))))
                               cache))]
    {:count          (count cache)
     :groups         (count by-key)
     :category-counts category-counts
     :invalid        invalid
     :conflicts      (vec conflicts)
     :variants       (vec variants)
     :missing-lowercase (vec missing-lower)}))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(defn- serialize [path data]
  (if (str/ends-with? (str/lower-case (str path)) ".edn")
    (with-out-str (pp/pprint data))
    (json/generate-string data {:pretty true})))

(defn- write-atomic! [path data]
  (let [target (fs/file path)
        parent (or (fs/parent target) (fs/file "."))]
    (fs/create-dirs parent)
    (let [tmp (java.io.File/createTempFile "ontology-" ".tmp" (fs/file parent))]
      (try
        (spit tmp (serialize target data))
        (try
          (Files/move (.toPath tmp) (.toPath target)
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/ATOMIC_MOVE
                                   StandardCopyOption/REPLACE_EXISTING]))
          (catch Exception _
            (Files/move (.toPath tmp) (.toPath target)
                        (into-array java.nio.file.CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING]))))
        (finally
          (when (fs/exists? tmp)
            (fs/delete tmp)))))))

(defn- append-audit! [path entries]
  (when (and (not (blank? path)) (seq entries))
    (let [target (fs/file path)
          parent (or (fs/parent target) (fs/file "."))]
      (fs/create-dirs parent)
      (spit target
            (str (str/join "\n" (map #(json/generate-string %) entries)) "\n")
            :append true))))

(defn- audit-entry [client event m]
  (merge {:ts       (now-iso)
          :event    event
          :provider "deepseek"
          :model    (:model client)
          :base_url (:base-url client)}
         m))

;; ---------------------------------------------------------------------------
;; Output
;; ---------------------------------------------------------------------------

(defn- print-plan [ctx plan client opts]
  (println "Ontology classifier (DeepSeek)")
  (println (str "  Base dir:    " (:base-dir ctx)))
  (println (str "  Config:      " (get-in ctx [:paths :config])))
  (println (str "  Candidates:  " (get-in ctx [:paths :candidates])))
  (println (str "  Cache:       " (get-in ctx [:paths :cache])))
  (println (str "  Audit log:   " (if (:no-audit opts) "(disabled)"
                                   (get-in ctx [:paths :audit]))))
  (println (str "  Model:       " (:model client) " @ " (:base-url client)))
  (println)
  (println (format "  Candidates: %d raw, %d unique, %d cached, %d filtered, %d canonical, %d pending"
                   (:raw-count plan)
                   (count (:unique plan))
                   (count (:cached plan))
                   (count (:filtered plan))
                   (count (:pre-resolved plan))
                   (count (:pending plan))))
  (println (format "  Cache:      %d entries" (count (:cache ctx))))
  (when (seq (:filtered-breakdown plan))
    (println (str "  Noise:      "
                  (str/join ", " (map (fn [[r n]] (str (name r) "=" n))
                                      (sort-by (comp str key) (:filtered-breakdown plan)))))))
  (when (seq (:pending plan))
    (println (str "  Pending:    " (str/join ", " (take 12 (:pending plan)))
                  (when (> (count (:pending plan)) 12) ", ..."))))
  (when (> (long (or (:pending-total plan) 0)) (count (:pending plan)))
    (println (format "  Limit:      %d of %d pending term(s) selected by --limit"
                     (count (:pending plan)) (:pending-total plan))))
  (println))

(defn- print-dry [ctx plan client]
  (println "Dry run — no API calls and no writes.")
  (println)
  (println (format "  Would send %d term(s) in %d batch(es) of %d."
                   (count (:pending plan))
                   (if (seq (:pending plan))
                     (long (Math/ceil (/ (count (:pending plan))
                                         (double (:batch-size client)))))
                     0)
                   (:batch-size client)))
  (when (seq (:pre-resolved plan))
    (println (str "  Canonical (no API): " (count (:pre-resolved plan))))
    (doseq [e (take 10 (:pre-resolved plan))]
      (println (format "    %s -> %s [%s]" (:raw-term e) (:normalized e) (:category e)))))
  (when (seq (:filtered plan))
    (println "  Filtered (first 10):")
    (doseq [e (take 10 (:filtered plan))]
      (println (format "    %s (%s)" (:term e) (name (:reason e))))))
  (when (:normalize-cache (:opts ctx))
    (let [groups (:canonical (existing-groups (:cache ctx) (:categories ctx)))
          normalized (normalize-existing-cache (:cache ctx) (:categories ctx) groups)
          changed (filter (fn [k] (not= (get (:cache ctx) k) (get normalized k)))
                          (keys (:cache ctx)))]
      (println (format "  --normalize-cache would rewrite %d existing cache value(s)."
                       (count changed)))
      (doseq [k (take 10 changed)]
        (println (format "    %s: %s -> %s" k
                         (get-in (:cache ctx) [k :normalized])
                         (get-in normalized [k :normalized]))))))
  (println (str "  API key: " (if (blank? (:api-key client)) "not set (not needed for dry-run)" "set")))
  (println)
  (println "  Re-run without --dry-run to classify and write the cache."))

(defn- print-summary [ctx plan result diff consistency]
  (println)
  (println "Summary")
  (println (format "  Candidates: %d raw, %d unique" (:raw-count plan) (count (:unique plan))))
  (println (format "  Skipped:    %d cached, %d filtered" (count (:cached plan)) (count (:filtered plan))))
  (println (format "  Classified: %d accepted, %d rejected" (count (:accepted result)) (count (:rejected result))))
  (println (format "  Cache:      %d -> %d entries (+%d/-%d, %d updated)"
                   (:before diff) (:after diff) (:added diff) (:removed diff) (:updated diff)))
  (when (seq (:conflicts consistency))
    (println (format "  Conflicts:  %d category conflict(s)" (count (:conflicts consistency))))
    (doseq [c (take 5 (:conflicts consistency))]
      (println (format "    %s -> %s (existing %s)"
                       (str/join ", " (:normalized c))
                       (:chosen c)
                       (or (:existing-category c) "none")))))
  (when (seq (:collapsed consistency))
    (println (format "  Collapsed:  %d near-duplicate group(s)" (count (:collapsed consistency)))))
  (when (seq (:overrides consistency))
    (println (format "  Enforced:   %d normalized/category override(s) to keep the cache consistent"
                     (count (:overrides consistency))))
    (doseq [o (take 5 (:overrides consistency))]
      (println (format "    %s -> %s [%s]"
                       (:raw-term o) (:normalized o) (:category o)))))
  (when (seq (:existing consistency))
    (println (format "  Warning:    %d invalid existing cache entry/entries left untouched"
                     (count (:existing consistency)))))
  (when-let [missing (seq (filter #(= :missing (:reason %)) (:rejected result)))]
    (println (format "  Missing:    %d term(s) could not be classified after retries" (count missing)))
    (doseq [m (take 10 missing)]
      (println (str "    " (:raw_term m)))))
  (when-not (:no-audit (:opts ctx))
    (println (str "  Audit log:  " (get-in ctx [:paths :audit]))))
  (when-let [r (get-in ctx [:paths :report])]
    (println (str "  Report:     " r)))
  (println (str "  Cache file: " (get-in ctx [:paths :cache]))))

;; ---------------------------------------------------------------------------
;; Modes
;; ---------------------------------------------------------------------------

(defn- check-mode [ctx]
  (let [cache       (:cache ctx)
        categories  (:categories ctx)
        cfg         (:config ctx)
        cache-check (validate-cache cache categories)
        plan        (plan-run ctx)
        alias-cats  (keep :category (vals (:aliases ctx)))
        bad-alias-cats (remove (set categories) alias-cats)]
    (println "Ontology check")
    (println (str "  Base dir:    " (:base-dir ctx)))
    (println (str "  Config:      " (get-in ctx [:paths :config])))
    (println (str "  Candidates:  " (get-in ctx [:paths :candidates])))
    (println (str "  Cache:       " (get-in ctx [:paths :cache])))
    (println (str "  Categories:  " (str/join ", " categories)))
    (println (str "  Schema:      v" (:schema_version cfg)))
    (println)
    (println (format "  Cache entries: %d (%d normalization groups)"
                     (:count cache-check) (:groups cache-check)))
    (println (str "  Cache categories: "
                  (str/join ", " (map (fn [[c n]] (str c "=" n)) (:category-counts cache-check)))))
    (println (format "  Invalid cache entries: %d" (count (:invalid cache-check))))
    (println (format "  Category conflicts: %d" (count (:conflicts cache-check))))
    (println (format "  Near-duplicate spelling groups: %d" (count (:variants cache-check))))
    (println (format "  Missing lowercase pair: %d" (count (:missing-lowercase cache-check))))
    (println (format "  Invalid alias categories: %d" (count bad-alias-cats)))
    (println)
    (println (format "  Candidates: %d raw, %d unique, %d cached, %d filtered, %d canonical, %d pending"
                     (:raw-count plan)
                     (count (:unique plan))
                     (count (:cached plan))
                     (count (:filtered plan))
                     (count (:pre-resolved plan))
                     (count (:pending plan))))
    (when (seq (:filtered-breakdown plan))
      (println (str "  Noise: " (str/join ", "
                                         (map (fn [[r n]] (str (name r) "=" n))
                                              (sort-by (comp str key) (:filtered-breakdown plan)))))))
    (when (seq (:variants cache-check))
      (println "  Spelling variants (first 10):")
      (doseq [v (take 10 (:variants cache-check))]
        (println (format "    %s: %s" (:key v) (str/join " | " (:normalized v))))))
    (when (seq (:conflicts cache-check))
      (println "  Conflicts (first 10):")
      (doseq [v (take 10 (:conflicts cache-check))]
        (println (format "    %s: %s" (:key v) (str/join " | " (:categories v))))))
    (when (seq (:invalid cache-check))
      (println "  Invalid entries (first 10):")
      (doseq [v (take 10 (:invalid cache-check))]
        (println (str "    " (pr-str (:value v)) " (" (name (:reason v)) ")"))))
    (when (seq (:missing-lowercase cache-check))
      (println "  Missing lowercase keys (first 10):")
      (doseq [k (take 10 (:missing-lowercase cache-check))]
        (println (str "    " k))))
    (let [fatal? (or (seq (:invalid cache-check))
                     (seq (:conflicts cache-check))
                     (seq bad-alias-cats))]
      (when-let [rp (get-in ctx [:paths :report])]
        (write-atomic! rp
                       {:generated_at (now-iso)
                        :mode         "check"
                        :base_dir     (:base-dir ctx)
                        :paths        (:paths ctx)
                        :categories   categories
                        :schema_version (:schema_version cfg)
                        :cache        cache-check
                        :invalid_alias_categories (vec bad-alias-cats)
                        :candidates   {:raw     (:raw-count plan)
                                       :unique  (count (:unique plan))
                                       :cached  (count (:cached plan))
                                       :filtered (:filtered plan)
                                       :canonical (count (:pre-resolved plan))
                                       :pending (count (:pending plan))}
                        :fatal        (boolean fatal?)}))
      (println)
      (println (if fatal? "CHECK FAILED" "CHECK OK"))
      (if fatal? 1 0))))

(defn- classify-chunk!
  "Classify a single batch.  Retries missing/invalid terms up to :max-retries
   and splits truncated responses.  Returns {:accepted [...] :rejected [...]}."
  [client terms]
  (loop [remaining (vec terms)
         attempt   0
         accepted  []
         rejected  []]
    (if (empty? remaining)
      {:accepted accepted :rejected rejected}
      (let [{:keys [content finish-reason]}
            (call-deepseek! client (build-messages client remaining))]
        (if (= "length" finish-reason)
          (if (> (count remaining) 1)
            (let [half  (quot (count remaining) 2)
                  left  (classify-chunk! client (subvec remaining 0 half))
                  right (classify-chunk! client (subvec remaining half))]
              {:accepted (into accepted (concat (:accepted left) (:accepted right)))
               :rejected (into rejected (concat (:rejected left) (:rejected right)))})
            (throw (ex-info "Model output truncated at max_tokens for a single term"
                            {:term (first remaining)})))
          (let [{new-accepted :accepted
                 new-rejected :rejected
                 missing      :missing}
                (validate-batch client remaining content)
                ;; Transient schema problems are retried; only permanent
                ;; model mistakes are reported as rejections.
                new-rejected (vec (remove #(#{:blank-normalized :invalid-category}
                                             (:reason %))
                                          new-rejected))
                accepted (into accepted new-accepted)
                rejected (into rejected new-rejected)]
            (cond
              (empty? missing)
              {:accepted accepted :rejected rejected}

              (< attempt (:max-retries client))
              (do
                (println (format "    Retrying %d missing/invalid term(s) (attempt %d/%d)..."
                                 (count missing) (inc attempt) (:max-retries client)))
                (Thread/sleep (:retry-backoff-ms client))
                (recur missing (inc attempt) accepted rejected))

              :else
              {:accepted accepted
               :rejected (into rejected
                               (map #(hash-map :raw_term % :reason :missing) missing))})))))))

(defn- classify-all! [client terms]
  (let [chunks (vec (partition-all (:batch-size client) terms))]
    (loop [idx 1
           chunks chunks
           accepted []
           rejected []]
      (if (empty? chunks)
        {:accepted accepted :rejected rejected}
        (let [chunk (vec (first chunks))
              result (classify-chunk! client chunk)]
          (println (format "  Batch %d/%d: %d sent, %d accepted, %d rejected"
                           idx (count (vec (partition-all (:batch-size client) terms)))
                           (count chunk)
                           (count (:accepted result))
                           (count (:rejected result))))
          (when (next chunks)
            (Thread/sleep (:delay-ms client)))
          (recur (inc idx) (rest chunks)
                 (into accepted (:accepted result))
                 (into rejected (:rejected result))))))))

(defn- run-mode [ctx]
  (let [opts         (:opts ctx)
        plan         (plan-run ctx)
        dry?         (boolean (:dry-run opts))
        need-api?    (and (not dry?) (seq (:pending plan)))
        client       (build-client ctx need-api?)
        cache-before (:cache ctx)
        _            (print-plan ctx plan client opts)]
    (if dry?
      (do
        (print-dry ctx plan client)
        0)
      (let [results      (if (seq (:pending plan))
                           (classify-all! client (:pending plan))
                           {:accepted [] :rejected []})
            all-accepted (into (vec (:pre-resolved plan)) (:accepted results))
            consistency  (enforce-consistency cache-before all-accepted (:categories ctx))
            entries      (:entries consistency)
            groups       (:canonical (existing-groups cache-before (:categories ctx)))
            cache-base   (if (:normalize-cache opts)
                           (normalize-existing-cache cache-before (:categories ctx) groups)
                           cache-before)
            cache-after  (merge-entries cache-base entries)
            changed?     (not= cache-before cache-after)
            diff         (diff-cache cache-before cache-after)
            audit        (atom [])
            client-ev    {:model (:model client) :base-url (:base-url client)}]
        (when changed?
          (write-atomic! (get-in ctx [:paths :cache]) cache-after))
        ;; Audit accepted classifications.
        (doseq [e entries]
          (swap! audit conj
                 (audit-entry client-ev "classified"
                              (cond-> {:raw_term   (:raw-term e)
                                       :normalized (:normalized e)
                                       :category   (:category e)
                                       :source     (:consistency-source e)
                                       :confidence (:confidence e)
                                       :llm_normalized (:llm-normalized e)
                                       :llm_category   (:llm-category e)}
                                (:normalized-overridden? e) (assoc :normalized_overridden true)
                                (:category-overridden? e) (assoc :category_overridden true)))))
        (doseq [r (:rejected results)]
          (swap! audit conj
                 (audit-entry client-ev "rejected"
                              {:raw_term (:raw_term r) :reason (:reason r)})))
        (doseq [c (:conflicts consistency)]
          (swap! audit conj
                 (audit-entry client-ev "conflict" c)))
        (when (:normalize-cache opts)
          (doseq [k (keys cache-before)
                  :let [old (get cache-before k)
                        new (get cache-base k)]
                  :when (not= old new)]
            (swap! audit conj
                   (audit-entry client-ev "cache-normalized"
                                {:raw_term k
                                 :from (:normalized old)
                                 :to   (:normalized new)
                                 :from_category (:category old)
                                 :to_category   (:category new)}))))
        (when changed?
          (swap! audit conj
                 (audit-entry client-ev "cache-write"
                              {:path   (get-in ctx [:paths :cache])
                               :before (:before diff)
                               :after  (:after diff)
                               :added  (:added diff)
                               :updated (:updated diff)
                               :removed (:removed diff)})))
        (when (and (seq @audit) (not (:no-audit opts)))
          (append-audit! (get-in ctx [:paths :audit]) @audit))
        (let [report {:generated_at (now-iso)
                      :base_dir     (:base-dir ctx)
                      :provider     "deepseek"
                      :model        (:model client)
                      :schema_version (:schema-version client)
                      :paths        (get-in ctx [:paths])
                      :counts       (merge {:raw-candidates (:raw-count plan)
                                            :unique-candidates (count (:unique plan))
                                            :cached (count (:cached plan))
                                            :filtered (count (:filtered plan))
                                            :pre_resolved (count (:pre-resolved plan))
                                            :pending (count (:pending plan))
                                            :accepted (count (:accepted results))
                                            :rejected (count (:rejected results))
                                            :conflicts (count (:conflicts consistency))
                                            :collapsed (count (:collapsed consistency))
                                            :overridden (count (:overrides consistency))
                                            :invalid-existing (count (:existing consistency))}
                                           (select-keys diff [:added :updated :removed :before :after]))
                      :filtered     (:filtered plan)
                      :rejected     (:rejected results)
                      :conflicts    (:conflicts consistency)
                      :collapsed    (:collapsed consistency)
                      :overrides    (:overrides consistency)
                      :cache        {:path (get-in ctx [:paths :cache])
                                     :before (:before diff) :after (:after diff)
                                     :added (:added diff) :updated (:updated diff)}}]
          (when-let [rp (get-in ctx [:paths :report])]
            (write-atomic! rp report)))
        (print-summary ctx plan results diff consistency)
        (when (seq (:existing consistency))
          (println (format "\nWarning: %d invalid existing cache entries were left untouched. Run --check for details."
                           (count (:existing consistency)))))
        (if (some #(= :missing (:reason %)) (:rejected results))
          1
          0)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [{:keys [opts args]} (cli/parse-args args {:spec cli-spec})]
    (when (:help opts)
      (print-usage)
      (System/exit 0))
    (when (seq args)
      (warn "Unexpected arguments:" (str/join " " args))
      (print-usage)
      (System/exit 2))
    (try
      (let [ctx (build-context opts)]
        (when (and (:check opts) (:dry-run opts))
          (warn "Both --check and --dry-run supplied; running --check only."))
        (let [code (cond
                     (:check opts)   (check-mode ctx)
                     :else           (run-mode ctx))]
          (flush)
          (System/exit code)))
      (catch clojure.lang.ExceptionInfo e
        (warn "ERROR:" (.getMessage e))
        (when-let [data (ex-data e)]
          (when (:verbose opts)
            (pp/pprint data)))
        (when (:verbose opts)
          (.printStackTrace e))
        (System/exit 1))
      (catch Throwable t
        (warn "ERROR:" (.getMessage t))
        (when (:verbose opts)
          (.printStackTrace t))
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
