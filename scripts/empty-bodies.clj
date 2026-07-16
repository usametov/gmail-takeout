#!/usr/bin/env bb
;; Extract email IDs with empty body-truncated from query EDN.
;; Usage: ./takeout query --limit 1000 --format edn | bb scripts/empty-bodies.bb > missing_ids.txt

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(let [data (edn/read-string (slurp *in*))
      results (:results data)
      empty (filter #(let [b (:email/body-truncated %)]
                       (or (nil? b) (= "" b)))
                    results)]
  (doseq [e empty]
    (println (str/replace (:email/id e) #"[<>]" "")))
  (binding [*out* *err*]
    (println "Found" (count empty) "emails with empty body out of" (count results) "total")))