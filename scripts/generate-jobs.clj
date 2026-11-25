#!/usr/bin/env bb
;; Generate job-posting entities from exported email JSON/EDN.
;;
;; The script is a thin wrapper around the pure parser in
;; src/astanova/entity/linkedin.clj.  It does not open Datalevin and does not
;; call an LLM; store its output with `takeout entities upsert`.
;;
;; Usage:
;;   bb scripts/generate-jobs.clj --from linkedin_emails.json \
;;     --out data/entities/jobs.edn
;;
;;   ./takeout -d emails.db query -l "..." --format edn \
;;     | bb scripts/generate-jobs.clj --out data/entities/jobs.edn
;;
;; Options:
;;   -f, --from PATH   Input JSON/EDN (stdin when omitted)
;;   -o, --out PATH    Output EDN (default: data/entities/jobs.edn)
;;   --source NAME     Source parser (default: linkedin)
;;   --help            Show this help

(ns ^{:clj-kondo/ignore [:namespace-name-mismatch]} scripts.generate-jobs
  (:require [babashka.cli :as cli]
            [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; Make src/ available when run from a source checkout.
(let [file (or *file* (System/getProperty "babashka.file"))]
  (when (and file (not (str/blank? (str file))))
    (let [root (-> (str file) fs/absolutize fs/parent fs/parent)]
      (cp/add-classpath (str (fs/path root "src"))))))

(require '[astanova.entity.linkedin :as linkedin])

(def cli-spec
  {:from   {:alias :f :desc "Input JSON/EDN (stdin if omitted)"}
   :out    {:alias :o :desc "Output EDN path" :default "data/entities/jobs.edn"}
   :source {:desc "Source parser" :default "linkedin"}
   :help   {:alias :h :desc "Show help"}})

(defn usage []
  (println "Generate job entities from email exports")
  (println)
  (println "Usage:")
  (println "  bb scripts/generate-jobs.clj [--from FILE] [--out FILE] [--source linkedin]")
  (println)
  (println "Options:")
  (doseq [[k {:keys [alias desc default]}] (sort-by (comp str key) cli-spec)]
    (println (format "  %-18s %s%s"
                     (str (when alias (str "-" (name alias) ", "))
                          "--" (name k))
                     (or desc "")
                     (if default (str " (default: " default ")") ""))))
  (println)
  (println "Input may be a JSON array, an EDN vector, or a takeout query map")
  (println "with :results.  The output is meant for `takeout entities upsert`."))

(defn parse-text [text path]
  (let [json? (or (and path (str/ends-with? (str/lower-case (str path)) ".json"))
                  (str/starts-with? (str/trim text) "[" )
                  (str/starts-with? (str/trim text) "{"))]
    (try
      (if json?
        (json/parse-string text true)
        (edn/read-string text))
      (catch Exception _
        ;; Fall back to the other syntax for stdin/unknown extensions.
        (try
          (if json?
            (edn/read-string text)
            (json/parse-string text true))
          (catch Exception e
            (throw (ex-info (str "Could not parse input as JSON or EDN"
                                 (when path (str ": " path)))
                            {:path path} e))))))))

(defn as-emails [data]
  (cond
    (and (map? data) (vector? (:results data))) (:results data)
    (and (map? data) (vector? (:entities data))) (:entities data)
    (vector? data) data
    (sequential? data) (vec data)
    :else (throw (ex-info "Input must be a JSON/EDN array or a map with :results"
                          {:type (type data)}))))

(defn read-input [{:keys [from]}]
  (let [text (if from (slurp from) (slurp *in*))]
    (as-emails (parse-text text from))))

(defn write-output [path data]
  (let [target (fs/file path)
        parent (fs/parent target)]
    (when parent (fs/create-dirs parent))
    (spit target (with-out-str (pp/pprint data)))
    (str target)))

(defn -main [& args]
  (let [{:keys [opts args]} (cli/parse-args args {:spec cli-spec})]
    (when (:help opts)
      (usage)
      (System/exit 0))
    (when (seq args)
      (binding [*out* *err*] (println "Unexpected arguments:" (str/join " " args)))
      (usage)
      (System/exit 2))
    (when-not (= "linkedin" (:source opts "linkedin"))
      (binding [*out* *err*]
        (println "Unsupported source parser:" (:source opts)
                 "(only 'linkedin' is implemented)"))
      (System/exit 2))
    (try
      (let [emails (read-input opts)
            result (linkedin/parse-linkedin-emails emails)
            output {:generated-at (java.util.Date.)
                    :source "linkedin"
                    :input (:from opts)
                    :stats (:stats result)
                    :entities (:entities result)}
            path (when (:out opts) (write-output (:out opts) output))]
        (binding [*out* *err*]
          (println (format "Job entities: %d from %d emails (%d cards, %d jobs, %d skipped)"
                           (count (:entities result))
                           (get-in result [:stats :emails])
                           (get-in result [:stats :blocks])
                           (get-in result [:stats :jobs])
                           (get-in result [:stats :skipped])))
          (if path
            (println "Wrote:" path)
            (println "No --out specified; EDN was written to stdout.")))
        (when-not path
          (pp/pprint output))
        0)
      (catch Exception e
        (binding [*out* *err*] (println "ERROR:" (.getMessage e)))
        1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
