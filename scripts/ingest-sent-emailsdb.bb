#!/usr/bin/env bb

;; Populate ~/Documents/Takeout/sent-emails-latest.db by running takeout ingest
;; on Sent-001.part-000{i}.mbox for i in 1..21
;;
;; Usage: bb scripts/ingest-sent-emailsdb.bb

(require '[babashka.fs :as fs])
(require '[babashka.process :as p])

(def db-path  (fs/expand-home "~/Documents/Takeout/sent-emails-latest.db"))
(def mbox-dir (fs/expand-home "~/Documents/Takeout/Mail"))
(def takeout  (fs/expand-home "~/code/takeout/takeout"))

(println "Database:" db-path)
(println "MBOX dir:" mbox-dir)
(println)

(doseq [i (range 1 22)]
  (let [fname (format "Sent-001.part-%04d.mbox" i)
        mbox  (str mbox-dir "/" fname)]
    (if (fs/exists? mbox)
      (do
        (println (str "[" i "/21] " fname " ..."))
        (let [{:keys [exit out err]} (p/sh (str takeout) "-d" (str db-path) "ingest" mbox)]
          (println out)
          (when (pos? exit)
            (println "ERROR:" err))))
      (println (str "[" i "/21] " fname " — SKIPPED (not found)")))))

(println "Done.")
