#!/usr/bin/env bb
;; Fetch email bodies from Gmail API for emails with empty body.
;;
;; Reads EDN from stdin (query output), finds records where
;; :email/body-truncated is empty/has :email/gmail-id set,
;; calls `gws gmail +read --id <gmail-id>` for each, and writes
;; the results as EDN to an output file.
;;
;; Usage:
;;   ./takeout -d emails.db query -l "trading" --format edn \
;;     | bb scripts/fetch-bodies.clj -o bodies.edn --no-dry-run
;;
;; Output EDN format:
;;   {"<gmail-id>" {:body "..." :subject "..." :from "..." :date "..."}, ...}
;;
;; Options:
;;   -o, --out-file PATH   Output EDN file (default: bodies.edn)
;;   -d, --delay MS        Delay in ms between API calls (default: 500)
;;   -n, --limit N         Max emails to fetch (0 = unlimited)
;;   --no-dry-run          Actually call gws API

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

;; ─── CLI ───────────────────────────────────────────────────────

(defn parse-int [s]
  (Long/parseLong s))

(defn parse-opts [args]
  (loop [args args
         opts {:out-file "bodies.edn"
               :delay    500
               :limit    0
               :dry-run  true}]
    (if-let [arg (first args)]
      (case arg
        "-o"      (recur (nnext args) (assoc opts :out-file (second args)))
        "--out-file" (recur (nnext args) (assoc opts :out-file (second args)))
        "-d"      (recur (nnext args) (assoc opts :delay (parse-int (second args))))
        "--delay" (recur (nnext args) (assoc opts :delay (parse-int (second args))))
        "-n"      (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        "--limit" (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        "--no-dry-run" (recur (next args) (assoc opts :dry-run false))
        (do (println "Unknown option:" arg)
            (System/exit 1)))
      opts)))

;; ─── GWS command ───────────────────────────────────────────────

(defn fetch-body
  "Fetch a full email from Gmail API by internal ID.
   Returns {:body ... :subject ... :from ... :date ...} or nil on failure."
  [gmail-id]
  (let [{:keys [out err exit]}
        (try (p/sh "gws" "gmail" "+read" "--id" gmail-id
                   "--headers" "--format" "json")
             (catch Exception e
               (binding [*out* *err*]
                 (println "gws exec failed for" gmail-id ":" (.getMessage e)))
               {:exit 1 :out nil :err (str e)}))]
    (when (not= 0 exit)
      (binding [*out* *err*]
        (println "gws error for" gmail-id "(exit" exit "):" err)))
    (when out
      (try
        (let [msg (json/parse-string out true)]
          {:body    (:body_html msg)
           :subject (:subject msg)
           :from    (:from msg)
           :date    (:date msg)})
        (catch Exception e
          (binding [*out* *err*]
            (println "gws parse error for" gmail-id ":" (.getMessage e)))
          nil)))))

;; ─── Input parsing ─────────────────────────────────────────────

(defn items-from-input
  "Extract item maps from EDN input."
  [input]
  (cond
    (and (map? input) (:results input))
    (vec (:results input))
    (and (map? input) (:email/id input))
    [input]
    (or (seq? input) (vector? input))
    (vec input)
    :else
    (do (println "Expected EDN query result, got:" (type input))
        (System/exit 1))))

(defn needs-body?
  "Check if an item needs body fetching: gmail-id is set and
   body-truncated is nil/empty."
  [item]
  (and (some? (:email/gmail-id item))
       (or (nil? (:email/body-truncated item))
           (str/blank? (:email/body-truncated item)))))

;; ─── Main ──────────────────────────────────────────────────────

(defn -main [& args]
  (let [opts      (parse-opts args)
        out-file  (:out-file opts)
        delay-ms  (:delay opts)
        limit     (:limit opts)
        dry-run?  (:dry-run opts)

        ;; Read EDN from stdin
        input       (edn/read-string (slurp *in*))
        all-items   (items-from-input input)

        ;; Split: items needing body fetch vs not
        no-gmail    (remove :email/gmail-id all-items)
        have-body   (filter (fn [item]
                              (and (:email/gmail-id item)
                                   (not (str/blank? (:email/body-truncated item)))))
                          all-items)
        need-fetch  (filter needs-body? all-items)

        ;; Apply limit
        to-fetch    (cond->> need-fetch
                      (pos? limit) (take limit))
        to-fetch    (vec to-fetch)]

    (println (str "Need body fetch: " (count to-fetch) "/" (count all-items)
                 " (skip: " (count have-body) " already have body"
                 (when (seq no-gmail) (str ", " (count no-gmail) " missing gmail-id"))
                 ") (delay=" delay-ms "ms"
                 (when (pos? limit) (str ", limit=" limit))
                 (when dry-run? " [DRY-RUN]")
                 ")"))
    ;; Warn about missing gmail-id
    (when (seq no-gmail)
      (println "\nWARNING:" (count no-gmail) "emails missing :email/gmail-id — run map_gmail_ids.clj first:")
      (doseq [item (take 5 no-gmail)]
        (println "  " (:email/id item))))
    (println)
    (println "Output:" out-file)
    (println)
    (let [results    (atom {})
          start-time (System/currentTimeMillis)]

      (doseq [[i item] (map-indexed vector to-fetch)]
        (let [n (inc i)
              gmail-id (:email/gmail-id item)
              db-id    (:email/id item)]
          (when (and (> (count to-fetch) 5) (zero? (mod n 5)))
            (let [elapsed (- (System/currentTimeMillis) start-time)
                  rate    (double (/ n (max 1 (/ elapsed 1000.0))))]
              (println (format "  [%d/%d] %.0f/s ~%.0fs remaining"
                               n (count to-fetch) rate
                               (/ (max 0 (- (count to-fetch) n)) rate)))))

          (if dry-run?
            (swap! results assoc db-id
                   {:gmail-id gmail-id
                    :body     (str "body for " gmail-id)
                    :subject  (:email/subject item)
                    :from     (:email/from item)})
            (let [fetched (fetch-body gmail-id)]
              (swap! results assoc db-id
                     (assoc (or fetched {:error "fetch-failed"})
                            :gmail-id gmail-id
                            :db-id db-id))))
          (Thread/sleep delay-ms)))

      (println "\nWriting" (count @results) "entries to" out-file)
      (spit out-file (pr-str @results))

      (let [elapsed (quot (- (System/currentTimeMillis) start-time) 1000)
            fetched (filter (comp some? :body) (vals @results))
            failed  (filter (comp :error) (vals @results))
            fetched-count (count fetched)
            failed-count  (count failed)]
        (when (seq failed)
          (println "\nFailed entries:")
          (doseq [{:keys [gmail-id db-id error]} failed]
            (println (format "  gmail-id=%s msg-id=%s error=%s" gmail-id (subs (or db-id "?") 0 40) error))))
        (println "Done." fetched-count "fetched," failed-count "failed."
                 (str "(elapsed: " (quot elapsed 60) "m " (mod elapsed 60) "s)"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
