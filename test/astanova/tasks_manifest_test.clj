(ns astanova.tasks-manifest-test
  (:require [astanova.tasks.manifest :as manifest]
            [clojure.test :refer [deftest is testing]]))

(def valid
  (manifest/load-task "scripts/tasks/arxiv-agentic-kg/task.edn"))

(deftest test-load-valid-manifest
  (testing "the arXiv task manifest loads and normalizes"
    (is (= "arxiv-agentic-kg" (:task/id valid)))
    (is (= :include (get-in valid [:filter :mode])))
    (is (= "emails.json" (get-in valid [:output :emails])))
    (is (= true (get-in valid [:import :emails])))
    (is (= false (get-in valid [:import :assertions])))))

(deftest test-manifest-validation-errors
  (testing "blank query and duplicate/unknown rules are rejected"
    (let [bad-query (manifest/validate (assoc-in valid [:fetch :query] ""))
          duplicate (manifest/validate
                     (update-in valid [:extract :fields]
                                conj (first (get-in valid [:extract :fields]))))
          unknown (manifest/validate
                   (assoc-in valid [:filter :include-if :all] [:missing]))]
      (is (seq (:errors bad-query)))
      (is (some #(= [:fetch :query] (:path %)) (:errors bad-query)))
      (is (some #(= "duplicate field ids" (:message %)) (:errors duplicate)))
      (is (some #(= "references unknown rule ids" (:message %)) (:errors unknown))))))

(deftest test-manifest-requires-explicit-local-model
  (testing "local-model fields require LLM enablement"
    (let [local (assoc-in valid [:extract :fields 0 :field/backend] :local-model)
          result (manifest/validate local)]
      (is (some #(= [:llm :enabled] (:path %)) (:errors result))))))

(deftest test-manifest-summary
  (testing "summary exposes the reviewable task shape"
    (is (= [:paper/title :paper/abstract :paper/url :paper/authors :paper/categories]
           (:extract/fields (manifest/summary valid))))
    (is (= [:agentic :kg-ontology :declarative-query]
           (:filter/rules (manifest/summary valid))))))
