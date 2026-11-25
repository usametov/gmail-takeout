(ns astanova.import-emails-test
  (:require [astanova.db :as db]
            [astanova.import.emails :as import-emails]
            [astanova.ingest :as ingest]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "import-emails" (make-array FileAttribute 0))))

(defn- fixture []
  (json/parse-string (slurp "test/fixtures/antigravity-emails.json") true))

(deftest test-normalize-antigravity-email
  (let [{:keys [emails total skipped]}
        (import-emails/normalize-emails (fixture) "antigravity-json")
        plain (first (filter #(= "Plain import" (:subject %)) emails))
        html  (first (filter #(= "HTML import" (:subject %)) emails))]
    (is (= 2 total))
    (is (= 0 skipped))
    (testing "Gmail API hex is canonicalized"
      (is (= "1869976353534821376" (:gmail-id plain)))
      (is (= "1869976293963969653" (:thread-id plain))))
    (testing "plain body and links"
      (is (= "Plain body with https://example.com/a" (:body plain)))
      (is (= ["https://example.com/a"] (:links plain))))
    (testing "HTML body is split into html + text"
      (is (string? (:html html)))
      (is (= "Hello HTML" (:body html))))
    (testing "labels and date"
      (is (= #{"INBOX" "ai/agents"} (set (:labels plain))))
      (is (instance? java.util.Date (:date plain))))))

(deftest test-upsert-emails-idempotent
  (let [dir (temp-dir)
        conn (db/create-conn (str dir "/emails.db"))
        {:keys [emails]} (import-emails/normalize-emails (fixture) "antigravity-json")]
    (try
      (let [first-stats (ingest/upsert-emails! conn emails "antigravity-emails.json"
                                               "antigravity-json")
            second-stats (ingest/upsert-emails! conn emails "antigravity-emails.json"
                                                "antigravity-json")]
        (is (= 2 (:inserted first-stats)))
        (is (= 2 (:updated second-stats)))
        (is (= 2 (count (d/q '[:find ?e :where [?e :email/id _]] (d/db conn)))))
        (let [e (d/entity (d/db conn) [:email/id "<antigravity-1@test>"])]
          (is (= "1869976353534821376" (:email/gmail-id e)))
          (is (= "1869976293963969653" (:email/thread-id e)))
          (is (= #{"INBOX" "ai/agents"} (set (:email/labels e))))
          (is (= ["https://example.com/a"] (vec (:email/links e))))
          (is (false? (boolean (:email/html e))))))
      (finally (db/close-conn conn)))))

(deftest test-upsert-preserves-body-and-force
  (let [dir (temp-dir)
        conn (db/create-conn (str dir "/preserve.db"))
        base {:message-id "<preserve@test>"
              :subject "Preserve"
              :body "old body"
              :html "<p>old</p>"
              :labels ["old"]
              :links ["https://old.example"]}]
    (try
      (ingest/upsert-emails! conn [base] "f" "test")
      (let [replacement (assoc base :body "new body" :html "<p>new</p>"
                               :labels ["new"] :links ["https://new.example"])]
        (ingest/upsert-emails! conn [replacement] "f" "test")
        (let [e (d/entity (d/db conn) [:email/id "<preserve@test>"])]
          (is (= "old body" (:email/body-truncated e)))
          (is (= #{"old" "new"} (set (:email/labels e))))
          (is (= #{"https://old.example" "https://new.example"}
                 (set (:email/links e)))))
        (ingest/upsert-emails! conn [replacement] "f" "test" :force true)
        (let [e (d/entity (d/db conn) [:email/id "<preserve@test>"])]
          (is (= "new body" (:email/body-truncated e)))
          (is (= "<p>new</p>" (:email/html e)))))
      (finally (db/close-conn conn)))))

(deftest test-upsert-dry-run-does-not-write
  (let [dir (temp-dir)
        conn (db/create-conn (str dir "/dry.db"))
        {:keys [emails]} (import-emails/normalize-emails (fixture) "antigravity-json")]
    (try
      (let [stats (ingest/upsert-emails! conn emails "f" "test" :dry-run true)]
        (is (= 2 (:inserted stats)))
        (is (true? (:dry-run stats)))
        (is (zero? (count (d/q '[:find ?e :where [?e :email/id _]] (d/db conn))))))
      (finally (db/close-conn conn)))))
