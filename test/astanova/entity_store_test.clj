(ns astanova.entity-store-test
  (:require [astanova.db :as db]
            [astanova.entity.linkedin :as linkedin]
            [astanova.entity.store :as store]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "entity-store" (make-array FileAttribute 0))))

(defn- fixture-entities []
  (:entities (linkedin/parse-linkedin-emails
              (json/parse-string (slurp "test/fixtures/linkedin-jobs.json") true))))

(deftest test-upsert-entities-idempotent
  (let [dir (temp-dir)
        conn (db/create-conn (str dir "/entities.db"))
        entities (fixture-entities)]
    (try
      (let [first-stats (store/upsert-entities! conn entities)
            second-stats (store/upsert-entities! conn entities)
            db (d/db conn)]
        (testing "first run inserts"
          (is (= 3 (:entities first-stats)))
          (is (= 3 (:inserted first-stats)))
          (is (= 4 (:evidence first-stats))))
        (testing "rerun updates without duplicates"
          (is (= 0 (:inserted second-stats)))
          (is (= 3 (:updated second-stats)))
          (is (= 4 (:evidence second-stats))))
        (testing "entity and evidence facts exist in the DB"
          (is (= 3 (count (d/q '[:find ?e :where [?e :entity/id _]] db))))
          (is (= 4 (count (d/q '[:find ?e :where [?e :evidence/id _]] db))))
          (is (every? #(= :job-posting (:class/id (:entity/class %)))
                      (d/q '[:find [(pull ?e [:entity/title {:entity/class [:class/id]}]) ...]
                             :where [?e :entity/id _]]
                           db))))
        (testing "evidence points at its entity"
          (is (= 4 (count (d/q '[:find ?ev :where [?ev :evidence/entity _]] db))))))
      (finally (db/close-conn conn)))))

(deftest test-upsert-entities-dry-run
  (let [dir (temp-dir)
        conn (db/create-conn (str dir "/dry.db"))
        entities (fixture-entities)]
    (try
      (let [stats (store/upsert-entities! conn entities :dry-run true)]
        (is (= 3 (:entities stats)))
        (is (true? (:dry-run stats)))
        (is (zero? (count (d/q '[:find ?e :where [?e :entity/id _]] (d/db conn))))))
      (finally (db/close-conn conn)))))
