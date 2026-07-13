(ns astanova.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [astanova.db :as sut]))

(def ^:private test-db-path "/tmp/takeout-db-test")

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

(deftest test-create-conn
  (testing "Creating a connection opens the database"
    (let [conn (sut/create-conn test-db-path)]
      (is (instance? clojure.lang.Atom conn) "Should be an Atom (connection wrapper)")
      (is (some? (d/db conn)) "Should be able to get a DB from the connection")
      (d/close conn))))

(deftest test-close-conn
  (testing "Close a connection gracefully"
    (let [conn (sut/create-conn test-db-path)]
      (d/close conn)
      (is true "No exception on close"))))

(deftest test-basic-transact-and-query
  (testing "Basic transact and query round-trip"
    (let [conn   (sut/create-conn test-db-path)
          tx     (d/transact! conn [{:email/id       "msg-1"
                                     :email/subject  "Hello"
                                     :email/from     "alice@example.com"
                                     :email/date     (java.util.Date.)
                                     :email/body     "Hi there!"
                                     :email/source   "test"
                                     :email/mbox-file "test.mbox"
                                     :email/labels   ["inbox" "important"]}])
          db     (d/db conn)
          result (d/q '[:find ?s ?f
                        :where [?e :email/subject ?s]
                        [?e :email/from ?f]]
                      db)]
      (is (= #{["Hello" "alice@example.com"]} result)
          "Should retrieve transacted data")
      (d/close conn))))

(deftest test-email-schema
  (testing "Schema has expected attributes"
    (let [schema (sut/build-email-schema)]
      (is (contains? schema :email/id)       "Has :email/id")
      (is (contains? schema :email/subject)  "Has :email/subject")
      (is (contains? schema :email/from)     "Has :email/from")
      (is (contains? schema :email/date)     "Has :email/date")
      (is (contains? schema :email/body-truncated)  "Has :email/body-truncated")
      (is (contains? schema :email/labels)   "Has :email/labels"))))

(deftest test-email-attrs
  (testing "email-attrs covers all schema keys"
    (let [schema (sut/build-email-schema)
          attrs  (sut/get-email-attrs schema)]
      (is (= (set (keys schema)) attrs)
          "email-attrs should contain all schema attribute keys"))))

(deftest test-deduplication
  (testing "Duplicate :email/id is handled gracefully"
    (let [conn   (sut/create-conn test-db-path)
          email  {:email/id       "dup-1"
                  :email/subject  "First"
                  :email/from     "a@b.com"
                  :email/date     (java.util.Date.)
                  :email/body     "first body"
                  :email/source   "test"
                  :email/mbox-file "test.mbox"}]
      (d/transact! conn [email])
      (let [tx2    (d/transact! conn [(assoc email :email/subject "Second")])
            db     (d/db conn)
            result (d/q '[:find ?s :where [?e :email/subject ?s]] db)]
        (is (= #{["Second"]} result)
            "Duplicate id should upsert the latest value"))
      (d/close conn))))
