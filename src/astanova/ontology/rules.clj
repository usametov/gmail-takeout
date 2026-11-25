(ns astanova.ontology.rules
  "Pure, shared ontology rules.

   These functions are the single source of truth for category normalization,
   near-duplicate keys, noise filtering, and cache consistency.  They are used
   by the classifier, candidate compiler, and graph/cache validation; they do
   not read files or call a model."
  (:require [clojure.string :as str]))

(def default-categories
  ["companies" "roles" "fields" "job_types" "locations"])

(def default-df-threshold 0.25)

(def short-acronyms
  "Short terms explicitly allowed through the length filter."
  #{"AI" "ML" "UI" "UX" "IP"})

(def category-synonyms
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

(defn blank? [x]
  (str/blank? (str x)))

(defn normalize-category-name [c]
  (-> (str c) str/trim str/lower-case (str/replace #"[\s\-]+" "_")))

(defn clean-normalized
  "Normalize whitespace and reject blank / control-character / oversized
   normalized terms.  Returns nil when the value cannot be used as a key."
  [s]
  (when (string? s)
    (let [s (-> s str/trim (str/replace #"\s+" " "))]
      (when (and (seq s)
                 (<= (count s) 120)
                 (not (re-find #"[\u0000-\u001f\u007f]" s)))
        s))))

(defn normalization-key
  "Stable near-duplicate key: case, whitespace, hyphen, underscore, dot,
   apostrophe and slash variants collapse together.  Symbols such as + and #
   are preserved so C++ / C# / C stay distinct."
  [s]
  (when (string? s)
    (-> s str/trim str/lower-case
        (str/replace #"[\s\-_\.'’/]+" ""))))

(defn normalize-category [categories c]
  (when-not (blank? c)
    (let [c (normalize-category-name c)
          c (get category-synonyms c c)]
      (when (contains? (set categories) c) c))))

(defn preferred-normalized
  "Pick a deterministic canonical spelling for a group of normalized values.
   Preference: contains a space, contains a hyphen, longer, more frequent,
   earlier."
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

(defn preferred-category [categories]
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

(defn filter-sets [config]
  {:allow    (set (map #(str/lower-case (str %)) (:user_allow_list config)))
   :stops    (set (map #(str/lower-case (str %)) (:user_stop_list config)))
   :generics (set (map #(str/lower-case (str %)) (:standalone_generic config)))
   :df-stops (set (map #(str/lower-case (str %)) (:auto_detected_df_stops config)))})

(defn noise-reason
  "Return a keyword reason when a candidate is boilerplate/noise, nil when it
   should be classified.  The allow-list always wins."
  [sets config term df]
  (let [lc (str/lower-case term)
        threshold (or (:df_threshold config) default-df-threshold)]
    (cond
      (contains? (:allow sets) lc) nil
      (contains? (:stops sets) lc) :user-stop-list
      (contains? (:generics sets) lc) :standalone-generic
      (contains? (:df-stops sets) lc) :df-stop
      (and df (>= (double df) (double threshold))) :df-threshold
      (re-matches #"^[0-9_\-\.\&\s]+$" term) :numeric
      (> (count term) 40) :too-long
      (re-find #"^[A-Z][A-Za-z0-9+#&'’]*[.!?]\s+\S" term) :sentence-fragment
      (some #(str/includes? term %)
            ["=" "%" "?" "/" "\\" "_" "|" ";" ":" "$" "*" "#" "@"])
      :url-fragment
      (and (< (count term) 3) (not (contains? short-acronyms term))) :too-short
      :else nil)))

(defn candidate-term [c]
  (cond
    (string? c) c
    (map? c) (some-> (or (:term c) (:raw_term c) (:raw-term c)
                         (:value c) (:text c) (:candidate c))
                     str)
    :else (some-> c str)))

(defn candidate-df [c]
  (when (map? c)
    (let [v (or (:df c) (:document_frequency c) (:document-frequency c))]
      (when (number? v) (double v)))))

(defn cached-term? [cache term]
  (or (contains? cache term)
      (contains? cache (str/lower-case term))))

(defn alias-for [aliases term]
  (get aliases (str/lower-case term)))

(defn normalize-config
  "Normalize the subset of config used by the shared rules."
  [raw]
  (let [raw (if (map? raw) raw {})
        df  (or (:df_threshold raw) (:df-threshold raw) default-df-threshold)]
    (assoc raw
           :df_threshold
           (cond
             (number? df) (double df)
             (string? df) (try (Double/parseDouble df)
                               (catch Exception _ default-df-threshold))
             :else default-df-threshold)
           :user_allow_list (vec (or (:user_allow_list raw) (:user-allow-list raw) []))
           :user_stop_list (vec (or (:user_stop_list raw) (:user-stop-list raw) []))
           :standalone_generic (vec (or (:standalone_generic raw) (:standalone-generic raw) []))
           :auto_detected_df_stops (vec (or (:auto_detected_df_stops raw)
                                            (:auto-detected-df-stops raw) []))
           :canonical_aliases (or (:canonical_aliases raw) (:canonical-aliases raw) {})
           :categories (or (:categories raw) default-categories)
           :schema_version (or (:schema_version raw) (:schema-version raw) 1))))

(defn config-categories [config]
  (let [raw (:categories config)]
    (when-not (and (sequential? raw) (seq raw))
      (throw (ex-info "config :categories must be a non-empty array"
                      {:categories raw})))
    (let [cs (->> raw
                  (map #(let [n (normalize-category-name %)]
                          (get category-synonyms n n)))
                  (remove blank?)
                  distinct
                  vec)]
      (when (empty? cs)
        (throw (ex-info "config :categories must contain at least one category" {})))
      cs)))

(defn alias-index
  "Build a lowercase term -> {:normalized ... :category ...} index."
  [raw-aliases categories]
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
               :else (throw (ex-info
                             (str "canonical_aliases entry must be a string, pair or object: "
                                  term)
                             {:term term})))]
         (when (blank? normalized)
           (throw (ex-info (str "canonical_aliases entry is missing :normalized: " term)
                           {:term term})))
         (let [n (clean-normalized (str normalized))
               c (when-not (blank? category)
                   (normalize-category categories category))]
           (when-not n
             (throw (ex-info (str "canonical_aliases entry has an invalid normalized value: "
                                  term)
                             {:term term :normalized normalized})))
           (when (and (not (blank? category)) (nil? c))
             (throw (ex-info (str "canonical_aliases entry has an invalid category: " term)
                             {:term term :category category :allowed categories})))
           (assoc m (str/lower-case term) {:normalized n :category c})))))
   {}
   raw-aliases))

(defn cache-key->str [k]
  (if (keyword? k) (name k) (str k)))

(defn existing-groups
  "Group cache values by normalization key and detect invalid entries."
  [cache categories]
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

(defn validate-cache
  "Validate cache shape, categories, dual keys, and category consistency."
  [cache categories]
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
