(ns astanova.integration-test
  "Integration test: ingest a real MBOX file and verify the round-trip.
   Uses ~/Documents/Takeout/Mail/ai-chatbots-watson.mbox (5 emails).
   Tests cover parsing, ingestion, all query helpers, and thread operations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [datalevin.core :as d]
            [astanova.db :as db]
            [astanova.ingest :as ingest]
            [astanova.parse :as parse]))

(def ^:private mbox-path
  "/Users/asel/Documents/Takeout/Mail/ai/ai-chatbots-watson.mbox")

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

;; ─── Query Integration Tests ─────────────────────────────────────
;;
;; All tests below use the same 5-email MBOX file.  Known data:
;;   3 unique senders: Coursera, Medium Daily Digest, Ulan Sametov
;;   5 unique thread-ids, no shared threads
;;   Labels include "ai/chatbots/watson", "Archived", "education/coursera"

(defn- with-db
  "Helper: open connection, ingest, call (f conn db), then close.
   Returns the value of (f conn db)."
  [f]
  (let [conn (db/create-conn test-db-path)
        _    (ingest/ingest-mbox! conn mbox-path "query-test")
        db   (d/db conn)
        result (f conn db)]
    (db/close-conn conn)
    result))

;; ─── Basic Query Helpers ─────────────────────────────────────────

(deftest test-count-emails
  (testing "count-emails returns total"
    (with-db
      (fn [_conn db]
        (is (= 5 (db/count-emails db)))))))

(deftest test-get-all-senders
  (testing "get-all-senders returns unique senders"
    (with-db
      (fn [_conn db]
        (let [senders (db/get-all-senders db)]
          (is (= 3 (count senders)) "3 unique senders")
          (is (some #(str/includes? % "coursera.org") senders))
          (is (some #(= % "usametov@gmail.com") senders))
          (is (some #(= % "noreply@medium.com") senders)))))))

(deftest test-get-all-labels
  (testing "get-all-labels returns unique labels across all emails"
    (with-db
      (fn [_conn db]
        (let [labels (db/get-all-labels db)]
          (is (some #(str/includes? % "ai/chatbots/watson") labels))
          (is (some #(= "Archived" %) labels))
          (is (some #(str/includes? % "education/coursera") labels))
          (is (some #(str/includes? % "machine-learning") labels)))))))

(deftest test-get-email-by-id
  (testing "get-email-by-id retrieves a single email by Message-ID"
    (with-db
      (fn [_conn db]
        (let [;; get the first email's ID from a known subject
              emails (d/q '[:find [(pull ?e [:email/id :email/subject :email/from]) ...]
                            :where [?e :email/subject ?s]
                                   [(clojure.string/includes? ?s "New in IT")]]
                          db)
              msg-id (:email/id (first emails))
              email  (db/get-email-by-id db msg-id)]
          (is (some? email) "email found by id")
          (is (= (:email/id email) msg-id) "ids match")
          (is (string? (:email/subject email)))
          (is (string? (:email/from email)))
          (is (string? (:email/body email)))
          (is (instance? java.util.Date (:email/date email)))
          (is (= "query-test" (:email/source email)))
          (is (= "ai-chatbots-watson.mbox" (:email/mbox-file email))))))))

(deftest test-get-email-by-id-missing
  (testing "get-email-by-id returns nil for non-existent ID"
    (with-db
      (fn [_conn db]
        (is (nil? (db/get-email-by-id db "<nonexistent@example.com>")))))))

;; ─── Filtering & Search ─────────────────────────────────────────

(deftest test-query-by-subject
  (testing "query-by-subject finds emails by keyword (case-insensitive)"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-subject db "new in it")]
          (is (= 1 (count results)))
          (is (str/includes? (:email/subject (first results)) "New in IT")))))))

(deftest test-query-by-subject-case-insensitive
  (testing "query-by-subject is case-insensitive"
    (with-db
      (fn [_conn db]
        (let [upper (db/query-by-subject db "IBM")
              lower (db/query-by-subject db "ibm")
              mixed (db/query-by-subject db "Ibm")]
          (is (pos? (count upper)))
          (is (= (count upper) (count lower) (count mixed))))))))

(deftest test-query-by-subject-empty
  (testing "query-by-subject returns empty for non-matching keyword"
    (with-db
      (fn [_conn db]
        (is (empty? (db/query-by-subject db "xyznonexistent12345")))))))

(deftest test-query-by-subject-partial
  (testing "query-by-subject matches partial words"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-subject db "IBM")]
          (is (>= (count results) 2))
          (is (some #(str/includes? (:email/subject %) "IBM creates") results))
          (is (some #(str/includes? (:email/subject %) "Applied AI with Watson") results)))))))

(deftest test-query-by-sender
  (testing "query-by-sender finds emails from a specific sender"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-sender db "no-reply@m.mail.coursera.org")]
          (is (= 3 (count results)))
          (doseq [e results]
            (is (= "no-reply@m.mail.coursera.org" (:email/from e)))))))))

(deftest test-query-by-sender-empty
  (testing "query-by-sender returns empty for unknown sender"
    (with-db
      (fn [_conn db]
        (is (empty? (db/query-by-sender db "unknown@example.com")))))))

(deftest test-query-by-recipient
  (testing "query-by-recipient finds emails sent to a specific address"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-recipient db "usametov@gmail.com")]
          (is (pos? (count results)))
          (doseq [e results]
            (is (string? (:email/subject e)))))))))

(deftest test-query-by-recipient-empty
  (testing "query-by-recipient returns empty for unknown recipient"
    (with-db
      (fn [_conn db]
        (is (empty? (db/query-by-recipient db "nowhere@example.com")))))))

(deftest test-query-by-label
  (testing "query-by-label finds emails with a specific label"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-label db "ai/chatbots/watson")]
          (is (pos? (count results)))
          (doseq [e results]
            (is (some #(= "ai/chatbots/watson" %) (:email/labels e)))))))))

(deftest test-query-by-label-archived
  (testing "query-by-label finds Archived emails"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-label db "Archived")]
          (is (pos? (count results)))
          (doseq [e results]
            (is (some #(= "Archived" %) (:email/labels e)))))))))

(deftest test-query-by-label-empty
  (testing "query-by-label returns empty for non-existent label"
    (with-db
      (fn [_conn db]
        (is (empty? (db/query-by-label db "FakeLabelXYZ")))))))

(deftest test-query-by-date-range
  (testing "query-by-date-range finds emails within range"
    (with-db
      (fn [_conn db]
        (let [start (java.util.Date. 0)  ;; 1970
              end   (java.util.Date.)     ;; now
              results (db/query-by-date-range db start end)]
          (is (= 5 (count results)) "all 5 emails are within [1970, now]"))))))

(deftest test-query-by-date-range-narrow
  (testing "query-by-date-range narrow window returns subset"
    (with-db
      (fn [_conn db]
        (let [start (java.util.Date. 1566000000000)  ;; ~Aug 17 2019
              end   (java.util.Date. 1567200000000)   ;; ~Aug 30 2019
              results (db/query-by-date-range db start end)]
          (is (<= 1 (count results) 5))
          (doseq [e results]
            (is (instance? java.util.Date (:email/date e)))))))))

(deftest test-query-by-date-range-empty
  (testing "query-by-date-range returns empty for non-overlapping window"
    (with-db
      (fn [_conn db]
        (let [start (java.util.Date. 1700000000000)  ;; 2023 (well past the 2019 emails)
              end   (java.util.Date. 1730000000000)   ;; 2024
              results (db/query-by-date-range db start end)]
          (is (empty? results) "no emails in 2023-2024 range"))))))

(deftest test-search-emails
  (testing "search-emails finds matches in subject or body"
    (with-db
      (fn [_conn db]
        (let [results (db/search-emails db "IBM")]
          (is (pos? (count results)))
          (doseq [e results]
            (is (string? (:email/subject e)))))))))

(deftest test-search-emails-empty
  (testing "search-emails returns empty for non-matching term"
    (with-db
      (fn [_conn db]
        (is (empty? (db/search-emails db "zzzzyyyyyxxxxx")))))))

(deftest test-recent-emails
  (testing "recent-emails returns N most recent in descending order"
    (with-db
      (fn [_conn db]
        (let [results (db/recent-emails db 5)]
          (is (= 5 (count results)))
          (doseq [e results]
            (is (instance? java.util.Date (:email/date e))))
          ;; Verify descending order by converting to millis
          (let [times (map #(.getTime ^java.util.Date (:email/date %)) results)]
            (is (apply >= times) "dates in descending order")))))))

(deftest test-recent-emails-limited
  (testing "recent-emails respects limit"
    (with-db
      (fn [_conn db]
        (is (= 2 (count (db/recent-emails db 2))))
        (is (= 1 (count (db/recent-emails db 1))))
        (is (empty? (db/recent-emails db 0)))))))

;; ─── Analytics ───────────────────────────────────────────────────

(deftest test-top-senders
  (testing "top-senders returns ordered by count descending"
    (with-db
      (fn [_conn db]
        (let [results (db/top-senders db 5)]
          (is (seq results))
          (is (>= 5 (count results)))
          ;; Each element is [sender count]
          (doseq [[sender count] results]
            (is (string? sender))
            (is (pos? count)))
          ;; Coursera should be first (2 emails)
          (is (str/includes? (ffirst results) "coursera.org")))))))

(deftest test-top-senders-limited
  (testing "top-senders respects limit"
    (with-db
      (fn [_conn db]
        (is (= 2 (count (db/top-senders db 2))))
        (is (= 1 (count (db/top-senders db 1))))))))

(deftest test-top-labels
  (testing "top-labels returns labels ordered by count descending"
    (with-db
      (fn [_conn db]
        (let [results (db/top-labels db 10)]
          (is (seq results))
          (doseq [[label count] results]
            (is (string? label))
            (is (pos? count))))))))

(deftest test-top-labels-limited
  (testing "top-labels respects limit"
    (with-db
      (fn [_conn db]
        (is (= 2 (count (db/top-labels db 2))))
        (is (= 1 (count (db/top-labels db 1))))))))

;; ─── Thread Query Tests ─────────────────────────────────────────

(deftest test-get-thread-ids
  (testing "get-thread-ids returns thread IDs (this mbox has X-GM-THRID)"
    (with-db
      (fn [_conn db]
        (let [thread-ids (db/get-thread-ids db)]
          (is (pos? (count thread-ids)) "thread IDs present via X-GM-THRID")
          (is (every? some? thread-ids) "all thread IDs are non-nil")
          (is (every? string? thread-ids) "thread IDs are strings"))))))

(deftest test-get-thread-emails
  (testing "get-thread-emails returns emails in a thread ordered by date"
    (with-db
      (fn [_conn db]
        (let [thread-ids (db/get-thread-ids db)
              tid        (first thread-ids)
              emails     (db/get-thread-emails db tid)]
          (is (pos? (count emails)) "thread has at least 1 email")
          (doseq [e emails]
            (is (some? (:email/id e)))
            (is (string? (:email/subject e)))
            (is (string? (:email/from e)))
            (is (instance? java.util.Date (:email/date e)))))))))

(deftest test-get-thread-emails-ordered
  (testing "get-thread-emails returns emails ordered by date ascending"
    (with-db
      (fn [_conn db]
        (let [thread-ids (db/get-thread-ids db)
              tid        (first thread-ids)
              emails     (db/get-thread-emails db tid)
              dates      (map :email/date emails)]
          (is (apply <= dates) "dates in ascending order"))))))

(deftest test-get-thread-participants
  (testing "get-thread-participants returns unique from/to addresses"
    (with-db
      (fn [_conn db]
        (let [thread-ids  (db/get-thread-ids db)
              tid         (first thread-ids)
              participants (db/get-thread-participants db tid)]
          (is (set? participants))
          (is (pos? (count participants)))
          ;; At minimum the sender should be present
          (let [first-email (first (db/get-thread-emails db tid))]
            (is (contains? participants (:email/from first-email)))))))))

(deftest test-get-threads-by-participant
  (testing "get-threads-by-participant finds threads involving an address"
    (with-db
      (fn [_conn db]
        (let [threads (db/get-threads-by-participant db "usametov@gmail.com")]
          (is (pos? (count threads)) "finds threads where user is recipient")
          (is (every? string? threads) "returns thread IDs"))))))

(deftest test-get-threads-by-participant-sender
  (testing "get-threads-by-participant finds threads where address is sender or recipient"
    (with-db
      (fn [_conn db]
        (let [threads (db/get-threads-by-participant db "usametov@gmail.com")]
          (is (pos? (count threads)))
          (is (= 5 (count threads)) "participant in all 5 threads"))))))

(deftest test-get-threads-by-participant-empty
  (testing "get-threads-by-participant returns empty for unknown address"
    (with-db
      (fn [_conn db]
        (is (empty? (db/get-threads-by-participant db "nobody@example.com")))))))

(deftest test-get-thread-summary
  (testing "get-thread-summary returns full summary for a thread"
    (with-db
      (fn [_conn db]
        (let [thread-ids (db/get-thread-ids db)
              tid        (first thread-ids)
              summary    (db/get-thread-summary db tid)]
          (is (map? summary))
          (is (= tid (:thread-id summary)))
          (is (string? (:subject summary)))
          (is (pos? (:email-count summary)))
          (is (set? (:participants summary)))
          (is (instance? java.util.Date (:first-date summary)))
          (is (instance? java.util.Date (:last-date summary))))))))

(deftest test-get-thread-summary-missing
  (testing "get-thread-summary returns nil for non-existent thread"
    (with-db
      (fn [_conn db]
        (is (nil? (db/get-thread-summary db "nonexistent-thread-id")))))))

(deftest test-get-recent-threads
  (testing "get-recent-threads returns N most recent threads"
    (with-db
      (fn [_conn db]
        (let [threads (db/get-recent-threads db 5)]
          (is (= 5 (count threads)))
          (is (every? map? threads))
          ;; Verify descending by :last-date
          (let [times (map #(.getTime ^java.util.Date (:last-date %)) threads)]
            (is (apply >= times) "threads sorted by last date descending")))))))

(deftest test-get-recent-threads-limited
  (testing "get-recent-threads respects limit"
    (with-db
      (fn [_conn db]
        (is (= 3 (count (db/get-recent-threads db 3))))
        (is (= 1 (count (db/get-recent-threads db 1))))))))

(deftest test-search-threads-by-subject
  (testing "search-threads-by-subject finds threads matching subject keyword"
    (with-db
      (fn [_conn db]
        (let [threads (db/search-threads-by-subject db "IBM")]
          (is (>= (count threads) 2))
          (is (every? string? threads)))))))

(deftest test-search-threads-by-subject-empty
  (testing "search-threads-by-subject returns empty for no match"
    (with-db
      (fn [_conn db]
        (is (empty? (db/search-threads-by-subject db "zzzzyyyyyxxxxx")))))))

(deftest test-get-conversation-view
  (testing "get-conversation-view returns formatted conversation"
    (with-db
      (fn [_conn db]
        (let [thread-ids (db/get-thread-ids db)
              tid        (first thread-ids)
              view       (db/get-conversation-view db tid)]
          (is (pos? (count view)))
          (let [entry (first view)]
            (is (contains? entry :email))
            (is (contains? entry :index))
            (is (contains? entry :date))
            (is (contains? entry :from))
            (is (contains? entry :subject))
            (is (contains? entry :snippet))
            (is (zero? (:index entry)))
            (is (string? (:snippet entry)))))))))

;; ─── Edge Case: Empty Database ─────────────────────────────────

(deftest test-empty-database-queries
  (testing "all query helpers handle empty database gracefully"
    (let [conn (db/create-conn test-db-path)
          db   (d/db conn)]
      (is (zero? (db/count-emails db)))
      (is (empty? (db/get-all-senders db)))
      (is (empty? (db/get-all-labels db)))
      (is (empty? (db/get-thread-ids db)))
      (is (nil? (db/get-email-by-id db "<anything>")))
      (is (empty? (db/query-by-subject db "test")))
      (is (empty? (db/query-by-sender db "x@y.com")))
      (is (empty? (db/query-by-recipient db "x@y.com")))
      (is (empty? (db/query-by-label db "Important")))
      (is (empty? (db/query-by-date-range db (java.util.Date. 0) (java.util.Date.))))
      (is (empty? (db/search-emails db "test")))
      (is (empty? (db/recent-emails db 10)))
      (is (empty? (db/top-senders db 10)))
      (is (empty? (db/top-labels db 10)))
      (is (nil? (db/get-thread-summary db "nonexistent")))
      (is (empty? (db/get-threads-by-participant db "x@y.com")))
      (is (empty? (db/get-recent-threads db 10)))
      (is (empty? (db/search-threads-by-subject db "test")))
      (is (empty? (db/get-conversation-view db "nonexistent")))
      (db/close-conn conn))))

;; ─── Edge Case: Multi-Thread Data ──────────────────────────────

(deftest test-thread-isolation
  (testing "thread queries don't leak between threads"
    (with-db
      (fn [_conn db]
        (let [thread-ids (db/get-thread-ids db)]
          ;; Each thread-id should return only its own email
          (doseq [tid thread-ids]
            (let [emails (db/get-thread-emails db tid)]
              (is (= 1 (count emails)))
              (is (= tid (:email/thread-id (first emails)))))))))))

(deftest test-all-threads-count
  (testing "sum of all thread emails equals total emails"
    (with-db
      (fn [_conn db]
        (let [thread-ids (db/get-thread-ids db)
              total-in-threads (reduce + 0 (map #(count (db/get-thread-emails db %)) thread-ids))]
          (is (= (db/count-emails db) total-in-threads)))))))

;; ─── Multi-Label and Combined Queries ─────────────────────────
;; Known label counts from the 5-email MBOX:
;;   ai/chatbots/watson → 5 emails
;;   Archived           → 4 emails
;;   education/coursera → 3 emails
;;   IBM                → 3 emails

(deftest test-query-by-labels-any
  (testing "query-by-labels-any finds emails with ANY of the labels"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-labels-any db ["ai/chatbots/watson"])]
          (is (= 5 (count results)) "all 5 have watson label"))))))

(deftest test-query-by-labels-any-multiple
  (testing "query-by-labels-any matches any of the given labels"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-labels-any db ["education/coursera" "IBM"])]
          (is (= 4 (count results)) "4 emails have either coursera or IBM")
          (doseq [e results]
            (is (some #(or (= % "education/coursera")
                           (= % "IBM"))
                      (:email/labels e)))))))))

(deftest test-query-by-labels-any-no-match
  (testing "query-by-labels-any returns empty for non-existent labels"
    (with-db
      (fn [_conn db]
        (is (empty? (db/query-by-labels-any db ["FakeLabelXYZ"])))))))

(deftest test-query-by-labels-all
  (testing "query-by-labels-all finds emails with ALL specified labels"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-labels-all db ["ai/chatbots/watson" "education/coursera"])]
          (is (= 3 (count results)) "3 emails have both watson and coursera")
          (doseq [e results]
            (is (some #(= "ai/chatbots/watson" %) (:email/labels e)))
            (is (some #(= "education/coursera" %) (:email/labels e)))))))))

(deftest test-query-by-labels-all-no-match
  (testing "query-by-labels-all returns empty when no email has all labels"
    (with-db
      (fn [_conn db]
        (is (empty? (db/query-by-labels-all db ["ai/chatbots/watson" "FakeLabel"])) "no email has both")))))

(deftest test-query-by-labels-and-text
  (testing "query-by-labels-and-text combines label AND text search"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-labels-and-text db ["education/coursera"] "IBM")]
          (is (pos? (count results)))
          (doseq [e results]
            (is (some #(= "education/coursera" %) (:email/labels e)))
            (is (or (clojure.string/includes? (or (:email/subject e) "") "IBM")
                    (clojure.string/includes? (or (:email/body e) "") "IBM")))))))))

(deftest test-query-by-labels-and-text-multi-label
  (testing "query-by-labels-and-text with multiple labels AND text"
    (with-db
      (fn [_conn db]
        (let [results (db/query-by-labels-and-text db
                        ["ai/chatbots/watson" "education/coursera"] "AI")]
          (is (pos? (count results)))
          (doseq [e results]
            (is (some #(= "ai/chatbots/watson" %) (:email/labels e)))
            (is (some #(= "education/coursera" %) (:email/labels e)))
            (is (or (clojure.string/includes? (or (:email/subject e) "") "AI")
                    (clojure.string/includes? (or (:email/body e) "") "AI")))))))))

(deftest test-query-by-labels-and-text-no-match
  (testing "query-by-labels-and-text returns empty when no match"
    (with-db
      (fn [_conn db]
        (is (empty? (db/query-by-labels-and-text db ["education/coursera"] "XYZZZZ")))))))

(deftest test-query-by-labels-empty-db
  (testing "multi-label queries handle empty database"
    (let [conn (db/create-conn test-db-path)
          db   (d/db conn)]
      (is (empty? (db/query-by-labels-any db ["test"])))
      (is (empty? (db/query-by-labels-all db ["test"])))
      (is (empty? (db/query-by-labels-and-text db ["test"] "text")))
      (db/close-conn conn))))