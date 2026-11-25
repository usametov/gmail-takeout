#!/usr/bin/env bb
;; arXiv agentic KG/ontology task — scaffold.
;;
;; This first implementation validates and prints the task manifest.  Fetch,
;; extraction, filtering, and output writing will be added in the next phase.

(ns ^{:clj-kondo/ignore [:namespace-name-mismatch]} arxiv-agentic-kg.run
  (:require [babashka.cli :as cli]
            [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(let [file (or *file* (System/getProperty "babashka.file"))]
  (when (and file (not (str/blank? (str file))))
    (let [root (-> (str file) fs/absolutize fs/parent fs/parent fs/parent fs/parent)]
      (cp/add-classpath (str (fs/path root "src"))))))

(require '[astanova.tasks.manifest :as manifest])

(def task-path
  (str (fs/path (fs/parent (str *file*)) "task.edn")))

(def cli-spec
  {:validate {:desc "Only validate the manifest"}
   :help     {:alias :h :desc "Show help"}})

(defn usage []
  (println "arXiv agentic KG/ontology task (scaffold)")
  (println)
  (println "Usage:")
  (println "  bb scripts/tasks/arxiv-agentic-kg/run.clj [--validate]")
  (println)
  (println "This phase validates task.edn and prints the task shape.")
  (println "Fetch/extract/filter/output are not implemented yet."))

(defn -main [& args]
  (let [{:keys [opts]} (cli/parse-args args {:spec cli-spec})]
    (when (:help opts)
      (usage)
      (System/exit 0))
    (try
      (let [m (manifest/load-task task-path)]
        (if (:validate opts)
          (println "Manifest valid:" (:task/id m) "v" (:task/version m))
          (do
            (println "Task manifest valid")
            (pp/pprint (manifest/summary m))
            (println)
            (println "Scaffold only: fetch/extract/filter/output are not implemented yet.")))
        0)
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*]
          (println "ERROR:" (.getMessage e))
          (pp/pprint (ex-data e)))
        1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
