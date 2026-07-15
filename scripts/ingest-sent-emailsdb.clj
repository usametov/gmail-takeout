#!/usr/bin/env bb

;; Ingest all Sent-001 part files into ~/Documents/Takeout/sent-emails-latest.db
;;
;; Usage: ./scripts/ingest-sent-emailsdb.clj [--debug]
;;   --debug  Enable MIME tree debug logging to stderr

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(defn now []
  (java.time.LocalDateTime/now))

(defn timestamp []
  (str "[" (.format (now) java.time.format.DateTimeFormatter/ISO_LOCAL_TIME) "]"))

(def db-path  (fs/expand-home "~/Documents/Takeout/sent-emails-latest.db"))
(def mbox-dir (fs/expand-home "~/Documents/Takeout/Mail"))
(def takeout  (fs/expand-home "~/code/takeout/takeout"))

;; Parse --debug flag
(def debug? (some #{"--debug"} *command-line-args*))

(def files
  (for [i (range 1 22)
        :let [f (str mbox-dir "/" (format "Sent-001.part-%04d.mbox" i))]
        :when (fs/exists? f)]
    f))

(when (empty? files)
  (println (timestamp) "No MBOX files found in" mbox-dir)
  (System/exit 1))

(println (timestamp) "Ingesting" (count files) "files into" db-path)
(when debug?
  (println (timestamp) "Debug mode: MIME tree logging enabled"))
(println)

(let [start-time (now)
      args (cond-> [(str takeout) "-d" (str db-path) "ingest"]
             debug? (conj "--debug")
             :always (into (map str files)))
      {:keys [exit out err]}
      (apply p/sh args)]
  (println out)
  (when (and debug? (not (str/blank? err)))
    (println "=== STDERR ===")
    (println err)
    (println "=============="))
  (let [elapsed (java.time.Duration/between start-time (now))]
    (println (timestamp) (str "Elapsed: " (.toMinutes elapsed) "m " (.toSecondsPart elapsed) "s")))
  (when (pos? exit)
    (println (timestamp) "ERROR:" err)
    (System/exit exit)))

(println (timestamp) "Done.")