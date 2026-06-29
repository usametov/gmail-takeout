(ns astanova.db
  "Database schema and connection management for email ingestion."
  (:require [datalevin.core :as d]))

(def email-schema
  "Schema for email entities in Datalevin.
   Uses Message-ID as the unique identity key to support deduplication."
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

(def email-attrs
  "Set of all email attribute keywords (for pulling entire entities)."
  (set (keys email-schema)))

(defn create-conn
  "Create or open a Datalevin connection at the given directory path.
   Returns a connection ready for transactions and queries."
  [db-path]
  (d/get-conn db-path email-schema))

(defn close-conn
  "Gracefully close a Datalevin connection."
  [conn]
  (d/close conn))
