(ns astanova.db
  "Database schema and connection management for email ingestion."
  (:require [datalevin.core :as d]))

(defn build-email-schema
  "Schema for email entities in Datalevin.
   Uses Message-ID as the unique identity key to support deduplication."
  []
  {:email/id          {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/unique      :db.unique/identity
                       :db/doc         "Message-ID header value, globally unique per email"}
   :email/thread-id   {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "Thread-ID or References header for grouping"}
   :email/source      {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "Origin of the email, e.g. 'google-takeout'"}
   :email/mbox-file   {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "Name of the MBOX file this email came from"}
   :email/subject     {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "Email subject line"}
   :email/from        {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "Sender email address"}
   :email/to          {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/many
                       :db/doc         "To recipients (multiple values)"}
   :email/cc          {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/many
                       :db/doc         "CC recipients"}
   :email/date        {:db/valueType   :db.type/instant
                       :db/cardinality :db.cardinality/one
                       :db/doc         "Date the email was sent"}
   :email/body        {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "Plain text body content"}
   :email/html        {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "HTML body content (if available)"}
   :email/labels      {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/many
                       :db/doc         "Gmail labels or derived tags"}})

(defn get-email-attrs
  "Set of all email attribute keywords (for pulling entire entities)."
  [email-schema]
  (set (keys email-schema)))

(defn create-conn
  "Create or open a Datalevin connection at the given directory path.
   Returns a connection ready for transactions and queries."
  [db-path]
  (d/get-conn db-path (build-email-schema)))

(defn close-conn
  "Gracefully close a Datalevin connection."
  [conn]
  (d/close conn))

;; ─── Query Helper Functions ───────────────────────────────────────

(defn query-by-subject
  "Find emails whose subject contains the given string (case-insensitive)."
  [db pattern]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?pattern
         :where [?e :email/subject ?s]
                [(clojure.string/includes? (clojure.string/lower-case ?s) ?pattern)]]
       db (clojure.string/lower-case pattern)))

(defn query-by-sender
  "Find emails from a specific sender."
  [db sender]
  (d/q '[:find [(pull ?e [:email/subject :email/from :email/date :email/to]) ...]
         :in $ ?sender
         :where [?e :email/from ?sender]]
       db sender))

(defn query-by-recipient
  "Find emails sent to a specific recipient."
  [db recipient]
  (d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
         :in $ ?recipient
         :where [?e :email/to ?recipient]]
       db recipient))

(defn query-by-label
  "Find emails with a specific Gmail label."
  [db label]
  (d/q '[:find [(pull ?e [:email/subject :email/from :email/date :email/labels]) ...]
         :in $ ?label
         :where [?e :email/labels ?label]]
       db label))

(defn query-by-date-range
  "Find emails within a date range (inclusive).
   Dates should be java.util.Date or java.time.Instant."
  [db start-date end-date]
  (d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
         :in $ ?start ?end
         :where [?e :email/date ?d]
                [(>= ?d ?start)]
                [(<= ?d ?end)]]
       db start-date end-date))

(defn query-by-thread
  "Find all emails in a thread by thread-id."
  [db thread-id]
  (d/q '[:find [(pull ?e [:email/subject :email/from :email/date :email/body]) ...]
         :in $ ?thread
         :where [?e :email/thread-id ?thread]]
       db thread-id))

(defn get-email-by-id
  "Get a single email entity by its Message-ID."
  [db message-id]
  (d/entity db [:email/id message-id]))

(defn count-emails
  "Count total emails in the database."
  [db]
  (d/q '[:find (count ?e) .
         :where [?e :email/subject]]
       db))

(defn get-all-labels
  "Get all unique labels in the database."
  [db]
  (d/q '[:find [?label ...]
         :where [?e :email/labels ?label]]
       db))

(defn get-all-senders
  "Get all unique senders in the database."
  [db]
  (d/q '[:find [?from ...]
         :where [?e :email/from ?from]]
       db))

(defn top-senders
  "Get top N senders by email count."
  [db n]
  (->> (d/q '[:find ?from (count ?e)
              :where [?e :email/from ?from]]
            db)
       (sort-by second >)
       (take n)))

(defn top-labels
  "Get top N labels by email count."
  [db n]
  (->> (d/q '[:find ?label (count ?e)
              :where [?e :email/labels ?label]]
            db)
       (sort-by second >)
       (take n)))

(defn search-emails
  "Full-text search across subject and body."
  [db term]
  (let [term-lc (clojure.string/lower-case term)]
    (d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
           :in $ ?term
           :where (or [?e :email/subject ?s]
                      [?e :email/body ?b])
                  [(clojure.string/includes? (clojure.string/lower-case ?s) ?term)]]
         db term-lc)))

(defn recent-emails
  "Get N most recent emails."
  [db n]
  (d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
         :where [?e :email/date ?d]
         :order-by [[?d :desc]]]
       db))
