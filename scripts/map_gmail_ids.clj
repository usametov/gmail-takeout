#!/usr/bin/env bb
;; Map Gmail RFC822 Message-IDs to Gmail internal IDs using gws CLI.
;;
;; Reads EDN from stdin (query output or list of email maps),
;; calls `gws gmail users messages list` for each Message-ID that
;; doesn't already have :email/gmail-id set, and writes the
;; mapping to an output EDN file.
;;
;; Usage:
;;   ./takeout -d ~/Documents/Takeout/sent-emails.db query -l "important" -n 100 --format edn \
;;     | bb scripts/map_gmail_ids.clj -o /tmp/gmail-ids.edn --no-dry-run
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
;;   --force               Re-fetch even if :email/gmail-id already set

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
               :dry-run  true
               :force    false}]
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
        "--force"      (recur (next args) (assoc opts :force true))
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

;; ─── Input parsing ─────────────────────────────────────────────

(defn items-from-input
  "Extract item maps from EDN input. Returns a vector of the raw item maps.
   Handles query output format {:total .. :results [...]}, single maps,
   and raw lists."
  [input]
  (cond
    ;; Query output format: {:total .. :results [{...}]}
    (and (map? input) (:results input))
    (vec (:results input))

    ;; Single map with :email/id
    (and (map? input) (:email/id input))
    [input]

    ;; Raw list/vector of maps or strings
    (or (seq? input) (vector? input))
    (vec input)

    :else
    (do (println "Expected EDN list, map, or query result, got:" (type input))
        (System/exit 1))))

(defn item->msg-id
  "Extract the :email/id string from an item map.
   Returns the Message-ID string, or the item itself if it's already a string."
  [item]
  (cond (map? item) (:email/id item)
        (string? item) item
        :else (str item)))

(defn item-already-has-gmail?
  "Check if an item already has :email/gmail-id set and non-nil."
  [item]
  (and (map? item) (some? (:email/gmail-id item))))

;; ─── Main ──────────────────────────────────────────────────────

(defn -main [& args]
  (let [opts      (parse-opts args)
        out-file  (:out-file opts)
        delay-ms  (:delay opts)
        limit     (:limit opts)
        skip      (:skip opts)
        user-id   (:user-id opts)
        dry-run?  (:dry-run opts)
        force?    (:force opts)

        ;; Read EDN from stdin
        input       (edn/read-string (slurp *in*))
        all-items   (items-from-input input)

        ;; Split: items that already have gmail-id vs those that need lookup
        ;; When --force, treat all items as needing lookup
        already-have (if force? [] (filter item-already-has-gmail? all-items))
        need-lookup  (if force? all-items (remove item-already-has-gmail? all-items))

        ;; Extract Message-IDs for items needing lookup
        lookup-ids  (->> need-lookup
                         (keep item->msg-id)
                         (remove str/blank?))

        ;; Apply skip/limit to lookup IDs only
        lookup-ids  (cond->> lookup-ids
                      true            (drop skip)
                      (pos? limit)    (take limit))
        lookup-ids  (vec lookup-ids)]

    (println (str "Processing " (count lookup-ids) " IDs"
                 (when (seq already-have) (str " (already have: " (count already-have) ")"))
                 (when force? " [FORCE]")
                 " (delay=" delay-ms "ms, skip=" skip
                 (when (pos? limit) (str ", limit=" limit))
                 (when dry-run? " [DRY-RUN]")
                 ")"))
    (println "Output:" out-file)
    (println)

    (let [results    (atom {})
          start-time (System/currentTimeMillis)]

      ;; Process each ID
      (doseq [[i mid] (map-indexed vector lookup-ids)]
        (let [n (inc i)]
          (when (and (> (count lookup-ids) 10) (zero? (mod n 10)))
            (let [elapsed    (- (System/currentTimeMillis) start-time)
                  rate       (double (/ n (max 1 (/ elapsed 1000.0))))]
              (println (format "  [%d/%d] %.0f/s ~%.0fs remaining"
                               n (count lookup-ids) rate
                               (/ (max 0 (- (count lookup-ids) n)) rate)))))

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
          (println "Skipped (already have) :" (count already-have))
          (println "Done." found "found," missing "missing."
                   (str "(elapsed: " (quot elapsed 60) "m " (mod elapsed 60) "s)")))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
