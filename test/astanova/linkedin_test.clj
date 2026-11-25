(ns astanova.linkedin-test
  (:require [astanova.entity :as entity]
            [astanova.entity.linkedin :as linkedin]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]))

(def fixture
  (json/parse-string (slurp "test/fixtures/linkedin-jobs.json") true))

(deftest test-linkedin-parser
  (let [{:keys [entities stats]} (linkedin/parse-linkedin-emails fixture)]
    (testing "all cards parse and duplicate jobs collapse"
      (is (= 2 (:emails stats)))
      (is (= 4 (:blocks stats)))
      (is (= 4 (:parsed stats)))
      (is (= 3 (:jobs stats)))
      (is (= 3 (count entities))))
    (testing "job fields and URLs are canonical"
      (let [insight (first (filter #(= "AI Engineer" (:job/title %)) entities))]
        (is (= "Insight" (:job/company-raw insight)))
        (is (= ["Toronto"] (:job/location-raw insight)))
        (is (= "4467426762" (:job/source-id insight)))
        (is (= ["https://www.linkedin.com/jobs/view/4467426762/"] (:job/url insight)))
        (is (= 2 (count (:entity/evidence insight))))
        (is (every? #(= :linkedin-email (:evidence/parser %))
                    (:entity/evidence insight)))))))

(deftest test-linkedin-parser-idempotent
  (testing "reparsing the same input produces identical entities"
    (is (= (:entities (linkedin/parse-linkedin-emails fixture))
           (:entities (linkedin/parse-linkedin-emails fixture))))))

(deftest test-linkedin-parser-ignores-other-senders
  (testing "non-LinkedIn email bodies are not parsed"
    (let [other (assoc-in (first fixture) [:from] "notify@example.com")
          result (linkedin/parse-linkedin-emails [other])]
      (is (= 0 (:emails (:stats result))))
      (is (empty? (:entities result))))))

(deftest test-linkedin-entity-validation
  (testing "generated entities pass structural validation"
    (let [{:keys [entities]} (linkedin/parse-linkedin-emails fixture)
          result (entity/validate-entities entities {})]
      (is (empty? (:errors result)))
      (is (empty? (:warnings result))))))
