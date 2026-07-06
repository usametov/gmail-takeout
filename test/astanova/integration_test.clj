(ns astanova.integration-test
  "Integration test: ingest a real MBOX file and verify the round-trip.
   Uses ~/Documents/Takeout/Mail/ai-chatbots-watson.mbox (5 emails)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [datalevin.core :as d]
            [astanova.db :as db]
            [astanova.ingest :as ingest]
            [astanova.parse :as parse]))

(def ^:private mbox-path
  "/Users/asel/Documents/Takeout/Mail/ai-chatbots-watson.mbox")

(def ^:private test-db-path
  "/tmp/takeout-integration-test")

(defn- delete-dir!
  "Recursively delete a directory."
  [path]
  (let [f (java.io.File. path)]
    (when (.exists f)
      (doseq [child (.listFiles f)]
        (.delete child))
      (.delete f))))

(defn cleanup [f]
  (delete-dir! test-db-path)
  (f)
  (delete-dir! test-db-path))

(use-fixtures :each cleanup)

;; ─── Parse layer tests ─────────────────────────────────────────

(deftest test-parse-mbox-count
  (testing "parse-mbox returns 5 emails"
    (let [emails (parse/parse-mbox mbox-path)]
      (is (= 5 (count emails))))))

(deftest test-parse-mbox-structure
  (testing "each email has all expected keys with values"
    (let [emails (parse/parse-mbox mbox-path)]
      (doseq [email emails]
        (is (string? (:message-id email)) "has :message-id")
        (is (string? (:subject email)) "has :subject")
        (is (string? (:from email)) "has :from")
        (is (instance? java.util.Date (:date email)) "has :date")
        (is (string? (:body email)) "has :body")
        (is (vector? (:labels email)) "has :labels")))))

(deftest test-parse-mbox-specific-email
  (testing "first email is from Coursera with known properties"
    (let [emails (parse/parse-mbox mbox-path)
          first-email (first emails)]
      (is (str/includes? (:from first-email) "coursera.org"))
      (is (str/includes? (:subject first-email) "New in IT"))
      (is (some #(str/includes? % "ai/chatbots/watson") (:labels first-email))
          "has watson label")
      (is (some #(str/includes? % "education/coursera") (:labels first-email))
          "has coursera label"))))

(deftest test-parse-mbox-body-non-nil
  (testing "each email has a non-empty body"
    (let [emails (parse/parse-mbox mbox-path)]
      (doseq [email emails]
        (is (pos? (count (:body email))) "body is non-empty")))))

;; ─── Ingestion layer tests ──────────────────────────────────────

(deftest test-ingest-mbox-count
  (testing "ingest-mbox! returns correct stats"
    (let [conn   (db/create-conn test-db-path)
          result (ingest/ingest-mbox! conn mbox-path "integration-test"
                                     :batch-size 10)]
      (is (= 5 (:email-count result)) "should ingest 5 emails")
      (is (pos? (:tx-count result)) "should have at least 1 tx")
      (db/close-conn conn))))

(deftest test-ingest-mbox-query-all
  (testing "5 emails are queryable after ingestion"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "integration-test")
          db     (d/db conn)
          cnt    (d/q '[:find (count ?e) :where [?e :email/subject]] db)]
      (is (= 5 (ffirst cnt)) "5 emails in DB")
      (db/close-conn conn))))

(deftest test-ingest-mbox-source
  (testing "all emails carry the source tag"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "integration-test")
          db     (d/db conn)
          result (d/q '[:find ?src :where [?e :email/source ?src]] db)
          cnt    (d/q '[:find (count ?e) :where [?e :email/source "integration-test"]] db)]
      (is (= #{["integration-test"]} (set result))
          "all emails have source = integration-test")
      (is (= 5 (ffirst cnt)) "all 5 emails carry the source"))))

(deftest test-ingest-mbox-mbox-file
  (testing "all emails record the mbox filename (not the full path)"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "test")
          db     (d/db conn)
          result (d/q '[:find ?f :where [?e :email/mbox-file ?f]] db)
          cnt    (d/q '[:find (count ?e) :where [?e :email/mbox-file "ai-chatbots-watson.mbox"]] db)]
      (is (= #{["ai-chatbots-watson.mbox"]} (set result))
          "all emails reference the correct mbox file")
      (is (= 5 (ffirst cnt)) "all 5 emails reference the file"))))

(deftest test-ingest-mbox-subjects
  (testing "specific emails can be found by subject"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "test")
          db     (d/db conn)
          result (d/q '[:find ?s :where [?e :email/subject ?s]] db)]
      (is (some #(str/includes? (first %) "New in IT") result)
          "Coursera marketing email present")
      (is (some #(str/includes? (first %) "IBM creates") result)
          "IBM Watson email present")
      (db/close-conn conn))))

(deftest test-ingest-mbox-labels
  (testing "Gmail labels are preserved in DB"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "test")
          db     (d/db conn)
          labels (d/q '[:find ?l :where [?e :email/labels ?l]] db)]
      (is (some #(str/includes? (first %) "ai/chatbots/watson") labels))
      (is (some #(= "Archived" (first %)) labels)))))

(deftest test-ingest-mbox-deduplication
  (testing "re-ingesting same file does not duplicate (upsert by :email/id)"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "test")
          before (d/q '[:find (count ?e) :where [?e :email/subject]]
                      (d/db conn))
          _      (ingest/ingest-mbox! conn mbox-path "test")
          after  (d/q '[:find (count ?e) :where [?e :email/subject]]
                      (d/db conn))]
      (is (= (ffirst before) (ffirst after))
          "count unchanged after re-ingest")
      (db/close-conn conn))))

;; ─── End-to-end: pull query ─────────────────────────────────────

(deftest test-end-to-end
  (testing "full round-trip: all 5 emails with all fields in pull query"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "e2e")
          db     (d/db conn)
          emails (d/q '[:find [(pull ?e [:*]) ...]
                        :where [?e :email/subject]]
                      db)]
      (is (= 5 (count emails)) "5 emails ingested")
      (doseq [e emails]
        (is (some? (:email/id e)) "has :email/id")
        (is (string? (:email/subject e)) "has :email/subject")
        (is (string? (:email/from e)) "has :email/from")
        (is (instance? java.util.Date (:email/date e)) "has :email/date")
        (is (string? (:email/body e)) "has :email/body")
        (is (string? (:email/source e)) "has :email/source")
        (is (string? (:email/mbox-file e)) "has :email/mbox-file"))
      (db/close-conn conn))))

;; ─── Edge case: custom batch sizes ──────────────────────────────

(deftest test-ingest-with-custom-batch-size
  (testing "batch-size 2 produces 3 transactions"
    (let [conn   (db/create-conn test-db-path)
          result (ingest/ingest-mbox! conn mbox-path "test" :batch-size 2)]
      (is (= 5 (:email-count result)))
      (is (= 3 (:tx-count result)))
      (db/close-conn conn))))

(deftest test-ingest-with-batch-size-1
  (testing "batch-size 1 produces 5 transactions"
    (let [conn   (db/create-conn test-db-path)
          result (ingest/ingest-mbox! conn mbox-path "test" :batch-size 1)]
      (is (= 5 (:email-count result)))
      (is (= 5 (:tx-count result)))
      (db/close-conn conn))))

(deftest test-ingest-with-large-batch
  (testing "batch-size larger than email count = 1 transaction"
    (let [conn   (db/create-conn test-db-path)
          result (ingest/ingest-mbox! conn mbox-path "test" :batch-size 100)]
      (is (= 5 (:email-count result)))
      (is (= 1 (:tx-count result)))
      (db/close-conn conn))))

;; ─── Upsert by source ───────────────────────────────────────────

(deftest test-ingest-upsert-updates-source
  (testing "re-ingesting with new source updates existing records"
    (let [conn   (db/create-conn test-db-path)
          _      (ingest/ingest-mbox! conn mbox-path "source-a")
          db1    (d/db conn)
          src-a  (set (d/q '[:find ?src :where [?e :email/source ?src]] db1))
          _      (ingest/ingest-mbox! conn mbox-path "source-b")
          db2    (d/db conn)
          srcs   (set (d/q '[:find ?src :where [?e :email/source ?src]] db2))]
      (is (= #{["source-a"]} src-a))
      (is (= #{["source-b"]} srcs)
          "re-ingest with new source upserts the old")
      (db/close-conn conn))))