#!/usr/bin/env bb
;; Map Gmail RFC822 Message-IDs to Gmail internal IDs using gws CLI.
;;
;; Reads EDN from stdin (list of :email/id values or list of maps),
;; calls `gws gmail users messages list` for each, and writes the
;; mapping to an output EDN file.
;;
;; Usage:
;;   ./takeout -d ~/Documents/Takeout/sent-emails.db query -l "important" -n 100 --format edn \
;;     | bb scripts/map_gmail_ids.bb -o /tmp/gmail-ids.edn --no-dry-run
;;
;; Output EDN format:
;;   {"<Message-ID>" {:gmail-id "..." :thread-id "..."}, ...}
;;
;; Options:
;;   -o, --out-file PATH   Output EDN file (default: gmail-ids.edn)
;;   -d, --delay MS        Delay in ms between API calls (default: 1000)
;;   -n, --limit N         Max number of IDs to process (0 = unlimited)
;;   -s, --skip N          Skip first N IDs (for resume)
;;   --user-id ID          Gmail user ID (default: me)
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
         opts {:out-file "gmail-ids.edn"
               :delay    1000
               :limit    0
               :skip     0
               :user-id  "me"
               :dry-run  true}]
    (if-let [arg (first args)]
      (case arg
        "-o"      (recur (nnext args) (assoc opts :out-file (second args)))
        "--out-file" (recur (nnext args) (assoc opts :out-file (second args)))
        "-d"      (recur (nnext args) (assoc opts :delay (parse-int (second args))))
        "--delay" (recur (nnext args) (assoc opts :delay (parse-int (second args))))
        "-n"      (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        "--limit" (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        "-s"      (recur (nnext args) (assoc opts :skip (parse-int (second args))))
        "--skip"  (recur (nnext args) (assoc opts :skip (parse-int (second args))))
        "--user-id" (recur (nnext args) (assoc opts :user-id (second args)))
        "--no-dry-run" (recur (next args) (assoc opts :dry-run false))
        (do (println "Unknown option:" arg)
            (System/exit 1)))
      opts)))

;; ─── GWS command ───────────────────────────────────────────────

(defn lookup-gmail-id
  "Look up a Gmail message by its RFC822 Message-ID header.
   Returns {:gmail-id ... :thread-id ...} or nil if not found."
  [message-id user-id]
  (let [params (json/generate-string
                {:userId user-id
                 :q (str "rfc822msgid:" message-id)})
        {:keys [out err exit]}
        (try (p/sh "gws" "gmail" "users" "messages" "list" "--params" params)
             (catch Exception e
               (binding [*out* *err*]
                 (println "gws exec failed for" message-id ":" (.getMessage e)))
               {:exit 1 :out nil :err (str e)}))]
    (when (not= 0 exit)
      (binding [*out* *err*]
        (println "gws error for" message-id "(exit" exit "):" err)))
    (when out
      (try
        (let [parsed (json/parse-string out true)]
          (when-let [msg (first (:messages parsed))]
            {:gmail-id  (:id msg)
             :thread-id (:threadId msg)}))
        (catch Exception e
          (binding [*out* *err*]
            (println "gws parse error for" message-id ":" (.getMessage e)))
          nil)))))

;; ─── Main ──────────────────────────────────────────────────────

(defn extract-ids
  "Extract email/message-id strings from EDN input.
   Handles both raw lists and query output format {:total ... :results [...]}."
  [input]
  (let [items (cond
                ;; Query output format: {:total .. :results [{:email/id ...}]}
                (and (map? input) (:results input))
                (:results input)

                ;; Single map with :email/id
                (and (map? input) (:email/id input))
                [input]

                ;; Raw list/vector
                (or (seq? input) (vector? input))
                input

                :else
                (do (println "Expected EDN list, map, or query result, got:" (type input))
                    (System/exit 1)))]
    (into [] (keep (fn [item]
                     (cond (map? item) (:email/id item)
                           (string? item) item
                           :else nil)))
          items)))

(defn -main [& args]
  (let [opts      (parse-opts args)
        out-file  (:out-file opts)
        delay-ms  (:delay opts)
        limit     (:limit opts)
        skip      (:skip opts)
        user-id   (:user-id opts)
        dry-run?  (:dry-run opts)

        ;; Read EDN from stdin
        input     (edn/read-string (slurp *in*))
        all-ids   (extract-ids input)
        ids       (cond->> all-ids
                    true            (drop skip)
                    true            (remove str/blank?)
                    (pos? limit)    (take limit))
        ids       (vec ids)]

    (println "Processing" (count ids) "IDs"
             (str "(delay=" delay-ms "ms, skip=" skip
                  (when (pos? limit) (str ", limit=" limit))
                  (when dry-run? " [DRY-RUN]") ")"))
    (println "Output:" out-file)
    (println)

    (let [results    (atom {})
          start-time (System/currentTimeMillis)]

      ;; Process each ID
      (doseq [[i mid] (map-indexed vector ids)]
        (let [n (inc i)]
          (when (and (> (count ids) 10) (zero? (mod n 10)))
            (let [elapsed    (- (System/currentTimeMillis) start-time)
                  rate       (double (/ n (max 1 (/ elapsed 1000.0))))
                  remaining  (/ (- elapsed) rate)
                  est-total  (/ (* elapsed (count ids)) (max 1 n))]
              (println (format "  [%d/%d] %.0f/s ~%.0fs remaining"
                               n (count ids) rate (/ remaining 1000.0))))))

        (if dry-run?
          (swap! results assoc mid
                 {:gmail-id  (str "gmail-" (Math/abs (hash mid)))
                  :thread-id (str "thread-" (Math/abs (hash mid)))})
          (let [found (lookup-gmail-id mid user-id)]
            (swap! results assoc mid (or found {:error "not-found"}))))
        (Thread/sleep delay-ms))

      ;; Write output
      (println "\nWriting" (count @results) "entries to" out-file)
      (spit out-file (pr-str @results))

      ;; Summary
      (let [elapsed     (quot (- (System/currentTimeMillis) start-time) 1000)
            found       (count (filter :gmail-id (vals @results)))
            missing     (count (filter :error (vals @results)))]
        (println "Done." found "found," missing "missing."
                 (str "(elapsed: " (quot elapsed 60) "m " (mod elapsed 60) "s)"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
