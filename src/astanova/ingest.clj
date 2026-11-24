(ns astanova.ingest
  "MBOX ingestion pipeline: parse → transform → transact into Datalevin."
  (:require [astanova.db :as db]
            [astanova.parse :as parse]
            [clojure.string :as str]
            [portal.api :as p]
            [datalevin.core :as d])
  (:import [java.io File]))

;; ─── Email map → Datalevin entity ────────────────────────────────

(defn email-map->entity
  "Convert a parsed email map (from astanova.parse/parse-raw-message)
   into a Datalevin transaction map matching db/email-schema.
   Uses :email/id (Message-ID) as the unique identity key.
   Only includes attributes that have non-nil values to avoid
   Datalevin rejecting nil for single-valued schema attributes."
  [email-map mbox-file source]
  (let [body (or (:body email-map) "")]
    (cond-> {:db/id                (d/tempid :db.part/user)
             :email/id             (:message-id email-map)
             :email/source         source
             :email/mbox-file      mbox-file
             :email/body-truncated (subs body 0 (min 10000 (count body)))
             :email/body-length    (count body)}
      (:gmail-id email-map)
      (assoc :email/gmail-id (:gmail-id email-map))
      (:subject email-map)
      (assoc :email/subject (:subject email-map))
      (:from email-map)
      (assoc :email/from (:from email-map))
      (:date email-map)
      (assoc :email/date (:date email-map))
      (seq (:labels email-map))
      (assoc :email/labels (:labels email-map))
      (:html email-map)
      (assoc :email/html (:html email-map))
      (:to email-map)
      (assoc :email/to (:to email-map))
      (:cc email-map)
      (assoc :email/cc (:cc email-map))
      (:thread-id email-map)
      (assoc :email/thread-id (:thread-id email-map))
      (seq (:attachments email-map))
      (assoc :email/attachments (:attachments email-map)))))

;; ─── Batch helpers ───────────────────────────────────────────────

(def ^:private default-batch-size 100)

(defn- partition-batches
  "Split a seq into batches of size n."
  [coll n]
  (partition n n nil coll))

;; ─── Ingestion ───────────────────────────────────────────────────

(defn ingest-emails!
  "Transact a seq of parsed email maps into Datalevin.
   Returns a map with :tx-count and :email-count stats.
   Handles deduplication via :email/id uniqueness."
  [conn emails mbox-file source & {:keys [batch-size]
                                   :or   {batch-size default-batch-size}}]
  (let [entities  (map #(email-map->entity % mbox-file source) emails)
        batches   (partition-batches entities batch-size)
        tx-count  (atom 0)
        total     (atom 0)]
    (doseq [batch batches]
      (let [n (count batch)]
        (d/transact! conn batch)
        (swap! tx-count inc)
        (swap! total + n)))
    {:tx-count    @tx-count
     :email-count @total}))

(defn ingest-mbox!
  "Parse an MBOX file and ingest all emails into Datalevin.
   Returns stats map with :tx-count, :email-count, and :file.

   Parameters:
     conn       - Datalevin connection (from db/create-conn)
     mbox-path  - path to the MBOX file
     source     - origin label, e.g. \"google-takeout\""
  [conn mbox-path source & {:keys [batch-size log-file]
                            :or   {batch-size default-batch-size}}]
  (let [mbox-file (.getName (File. mbox-path))
        raw-count (atom 0)
        err-count (atom 0)
        log-writer (when log-file
                     (java.io.FileWriter. (java.io.File. log-file) true))
        ;; Keep this lazy: ingest-emails! consumes it in batches so a large
        ;; chunk never has to realize every parsed message at once.
        parsed    (->> (parse/mbox-messages mbox-path)
                       (map (fn [entry]
                              (let [from-line (:from-line entry)]
                                (swap! raw-count inc)
                                (let [result (try
                                               (parse/parse-raw-message entry)
                                               (catch Throwable t
                                                 (when log-writer
                                                   (.write log-writer (str "--- Parse error #" @err-count " at raw #" @raw-count " ---\n"
                                                                          "From line: " from-line "\n"
                                                                          (.getMessage t) "\n")))
                                                 nil))]
                                  (when (nil? result)
                                    (swap! err-count inc)
                                    (when log-writer
                                      (try
                                        (.write log-writer (str "From line: " from-line "\n"))
                                        (catch Throwable _))))
                                  result))))
                       (filter some?)
                       (filter :message-id))]
    (try
      (let [stats (ingest-emails! conn parsed mbox-file source :batch-size batch-size)]
        (when log-writer
          (.write log-writer (str "\n--- Summary for " mbox-file " ---\n"
                                  "Total raw: " @raw-count "\n"
                                  "Parse errors: " @err-count "\n"
                                  "Ingested: " (:email-count stats) "\n\n")))
        (assoc stats :total-raw @raw-count :parse-errors @err-count))
      (finally
        (when log-writer
          (.close log-writer))))))

(defn ingest-mbox-files!
  "Ingest multiple MBOX files from a directory or list of paths.
   Returns aggregated stats across all files.

   Parameters:
     conn     - Datalevin connection
     mbox-dir - directory containing .mbox files, or seq of file paths
     source   - origin label"
  [conn mbox-dir source & {:keys [batch-size]
                           :or   {batch-size default-batch-size}}]
  (let [files (if (string? mbox-dir)
                (filter #(str/ends-with? (.getName %) ".mbox")
                        (.listFiles (File. mbox-dir)))
                (map #(File. %) mbox-dir))]
    (reduce (fn [stats f]
              (let [result (ingest-mbox! conn (.getAbsolutePath f) source
                                         :batch-size batch-size)]
                (println (format "  %s: %d emails in %d txs"
                                 (.getName f)
                                 (:email-count result)
                                 (:tx-count result)))
                (merge-with + stats result)))
            {:tx-count 0 :email-count 0}
            files)))

(comment
  (def p (p/open))

  (add-tap #'p/submit)

  (def mbox-path "/Users/asel/Documents/Takeout/Mail/ai-chatbots.mbox")

  (def emails (parse/parse-mbox mbox-path))

  (tap> emails))