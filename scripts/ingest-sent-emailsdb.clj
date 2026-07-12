#!/usr/bin/env bb

;; Ingest all Sent-001 part files into ~/Documents/Takeout/sent-emails-latest.db
;;
;; Usage: ./scripts/ingest-sent-emailsdb.clj

(require '[babashka.fs :as fs]
         '[babashka.process :as p])

(def db-path  (fs/expand-home "~/Documents/Takeout/sent-emails-latest.db"))
(def mbox-dir (fs/expand-home "~/Documents/Takeout/Mail"))
(def takeout  (fs/expand-home "~/code/takeout/takeout"))

(def files
  (for [i (range 1 22)
        :let [f (str mbox-dir "/" (format "Sent-001.part-%04d.mbox" i))]
        :when (fs/exists? f)]
    f))

(when (empty? files)
  (println "No MBOX files found in" mbox-dir)
  (System/exit 1))

(println "Ingesting" (count files) "files into" db-path)
(println)

(let [{:keys [exit out err]}
      (apply p/sh (str takeout) "-d" (str db-path) "ingest" files)]
  (print out)
  (when (pos? exit)
    (println "ERROR:" err)
    (System/exit exit)))

(println "Done.")