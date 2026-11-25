(ns astanova.entity-test
  (:require [astanova.db :as db]
            [astanova.entity :as entity]
            [astanova.ontology.graph :as graph]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "entity-test" (make-array FileAttribute 0))))

(deftest test-deterministic-entity-ids
  (testing "job ids are stable and month-sensitive"
    (let [job {:company "11x" :title "Senior Fullstack Engineer"
               :location "Toronto" :posted-at "2026-09-01T00:00:00Z"}]
      (is (= (entity/job-id job) (entity/job-id job)))
      (is (not= (entity/job-id job)
                (entity/job-id (assoc job :posted-at "2026-10-01T00:00:00Z"))))
      (is (not= (entity/job-id job)
                (entity/job-id (assoc job :company "Other"))))))
  (testing "program/episode/channel/evidence ids"
    (is (= "program:<msg@test>" (entity/program-id "<msg@test>")))
    (is (nil? (entity/program-id nil)))
    (is (= "episode:5BSMhbZTHAk" (entity/episode-id "5BSMhbZTHAk")))
    (is (nil? (entity/episode-id "bad-id")))
    (is (= "channel:boxing" (entity/channel-id "boxing")))
    (is (= (entity/evidence-id "job:1" "<e@test>" "https://x" :linkedin-email)
           (entity/evidence-id "job:1" "<e@test>" "https://x" :linkedin-email)))))

(deftest test-linkedin-url-normalization
  (testing "tracking parameters are removed"
    (is (= "https://www.linkedin.com/jobs/view/4377264410/"
           (entity/canonical-linkedin-job-url
            "https://www.linkedin.com/comm/jobs/view/4377264410/?trackingId=abc")))
    (is (= "4377264410"
           (entity/linkedin-job-source-id
            "https://www.linkedin.com/comm/jobs/view/4377264410/?trackingId=abc")))
    (is (nil? (entity/canonical-linkedin-job-url "https://example.com/jobs/1")))))

(deftest test-entity-validation
  (let [lookup {"term:11x" "companies"
                "term:engineer" "roles"
                "term:toronto" "locations"
                "term:ai" "fields"}
        job {:entity/id "job:1"
             :entity/class :job-posting
             :entity/title "Engineer"
             :job/title "Engineer"
             :job/company-raw "11x"
             :job/company {:term/id "term:11x" :term/category "companies"}
             :job/role {:term/id "term:engineer" :term/category "roles"}
             :job/location [{:term/id "term:toronto" :term/category "locations"}]
             :job/field [{:term/id "term:ai" :term/category "fields"}]}]
    (testing "valid job passes"
      (is (empty? (:errors (entity/validate-entity job lookup)))))
    (testing "category mismatch and bad class fail"
      (is (= :term-category-mismatch
             (:type (first (:errors (entity/validate-entity
                                     (assoc-in job [:job/company :term/category] "roles")
                                     lookup))))))
      (is (= :invalid-entity-class
             (:type (first (:errors (entity/validate-entity
                                     (assoc job :entity/class :widget) lookup)))))))
    (testing "episode video ids are validated"
      (is (= :invalid-video-id
             (:type (first (:errors (entity/validate-entity
                                     {:entity/id "episode:x"
                                      :entity/class :tv-episode
                                      :entity/title "X"
                                      :episode/video-id "bad"})))))))))

(deftest test-entity-schema-roundtrip
  (testing "entity and evidence facts store in the combined DB schema"
    (let [dir (temp-dir)
          conn (db/create-conn (str dir "/entities.db"))]
      (try
        (graph/seed-classes! conn (graph/load-schema graph/default-schema-path))
        (let [job-class (d/q '[:find ?e . :in $ ?id
                               :where [?e :class/id ?id]]
                             (d/db conn) :job-posting)
              job-id "job:test"]
          (d/transact! conn [{:entity/id job-id
                              :entity/class job-class
                              :entity/title "Test Job"
                              :entity/source :linkedin
                              :entity/status :active
                              :job/title "Test Job"
                              :job/company-raw "Acme"
                              :job/url ["https://example.com/job"]}])
          (let [job-eid (d/q '[:find ?e . :in $ ?id
                               :where [?e :entity/id ?id]]
                             (d/db conn) job-id)]
            (d/transact! conn [{:evidence/id "evidence:test"
                                :evidence/entity job-eid
                                :evidence/email-id "<e@test>"
                                :evidence/url "https://example.com/job"
                                :evidence/parser :linkedin-email}])
            (d/transact! conn [{:db/id job-eid
                                :entity/evidence (d/q '[:find ?e . :in $ ?id
                                                        :where [?e :evidence/id ?id]]
                                                      (d/db conn) "evidence:test")}])
            (let [job (d/pull (d/db conn)
                              '[:entity/title
                                {:entity/class [:class/id]}
                                {:entity/evidence [:evidence/id]}]
                              job-eid)]
              (is (= "Test Job" (:entity/title job)))
              (is (= :job-posting (:class/id (:entity/class job))))
              (is (= 1 (count (:entity/evidence job)))))))
        (finally (db/close-conn conn))))))
