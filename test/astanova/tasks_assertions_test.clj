(ns astanova.tasks-assertions-test
  (:require [astanova.tasks.assertions :as assertions]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]))

(def base
  {:assertion/subject-type :email
   :assertion/subject-id "<paper@test>"
   :assertion/field :paper/title
   :assertion/extractor-id "arxiv-paper-v1"
   :assertion/extractor-version 1
   :assertion/method :html-meta
   :assertion/raw-value "Original title"
   :assertion/locator {:mime "text/html" :path "meta[name=citation_title]"}
   :now (Instant/parse "2026-11-25T00:00:00Z")})

(deftest test-assertion-defaults-and-id
  (let [a (assertions/make base)]
    (testing "candidate defaults"
      (is (= :candidate (:assertion/status a)))
      (is (= 1 (:assertion/revision a)))
      (is (= "Original title" (:assertion/value a)))
      (is (= "Original title" (:assertion/raw-value a)))
      (is (string? (:assertion/id a)))
      (is (assertions/candidate? a)))
    (testing "ID is stable and context-sensitive"
      (is (= (:assertion/id a) (:assertion/id (assertions/make base))))
      (is (not= (:assertion/id a)
                (:assertion/id (assertions/make (assoc base :assertion/raw-value "Other")))))
      (is (not= (:assertion/id a)
                (:assertion/id (assertions/make (assoc base :assertion/extractor-version 2)))))
      (is (not= (:assertion/id a)
                (:assertion/id (assertions/make (assoc base :assertion/locator {:mime "text/plain"}))))))))

(deftest test-revision-id
  (let [a (assertions/make base)]
    (is (= (assertions/revision-id (:assertion/id a) 2 :approved)
           (assertions/revision-id (:assertion/id a) 2 :approved)))
    (is (not= (assertions/revision-id (:assertion/id a) 2 :approved)
              (assertions/revision-id (:assertion/id a) 3 :approved)))))

(deftest test-invalid-assertion
  (testing "invalid subject and status are rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (assertions/make (assoc base :assertion/subject-type :widget))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (assertions/make (assoc base :assertion/status :unknown))))))
