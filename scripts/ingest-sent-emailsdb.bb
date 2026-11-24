#!/usr/bin/env bb

;; Ingest all Sent-001 part files into ~/Documents/Takeout/sent-emails-latest.db
;;
;; Usage: bb scripts/ingest-sent-emailsdb.bb

(require '[babashka.fs :as fs]
         '[babashka.process :as p])

(def db-path  (fs/expand-home
               (or (System/getenv "TAKEOUT_DB")
                   "~/Documents/Takeout/sent-emails-latest.db")))
(def mbox-dir (fs/expand-home
               (or (System/getenv "TAKEOUT_MAIL_DIR")
                   "~/Documents/Takeout/Mail")))
(def takeout  (or (System/getenv "TAKEOUT_BIN")
                  (str (System/getProperty "user.dir") "/takeout")))

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