(ns astanova.ontology-graph-test
  (:require [astanova.ontology.graph :as graph]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "ontology-graph" (make-array FileAttribute 0))))

(defn- with-graph [f]
  (let [dir (temp-dir)
        path (str dir "/graph.db")
        conn (d/get-conn path (graph/graph-schema))]
    (try
      (graph/seed-classes! conn (graph/load-schema graph/default-schema-path))
      (f conn)
      (finally
        (d/close conn)))))

(defn- class-id [conn k]
  (d/q '[:find ?e . :in $ ?k :where [?e :class/id ?k]] (d/db conn) k))

(defn- add-term! [conn tid normalized category-k]
  (d/transact! conn [{:term/id tid
                      :term/normalized normalized
                      :term/category (class-id conn category-k)
                      :term/source :seed}])
  (d/q '[:find ?e . :in $ ?id :where [?e :term/id ?id]] (d/db conn) tid))

(deftest test-schema-and-rules-load
  (testing "schema and rules load from EDN"
    (let [schema (graph/load-schema graph/default-schema-path)
          rules  (graph/load-rules graph/default-rules-path)]
      (is (= 6 (count (:classes schema))))
      (is (= 10 (count (:disjoint schema))))
      (is (= 7 (count (:entity-classes schema))))
      (is (= 6 (count (:entity-disjoint schema))))
      (is (pos? (count rules)))
      (is (some #(= 'subclass (ffirst %)) rules))
      (is (contains? (graph/load-validation graph/default-rules-path)
                     :terms-without-category)))))

(deftest test-subclass-and-type-closure
  (with-graph
    (fn [conn]
      (let [rules (graph/load-rules graph/default-rules-path)
            company (class-id conn :companies)
            startup (class-id conn :organizations)]
        (testing "recursive subclass rules work in Datalevin"
          (is (= #{startup}
                 (set (map first
                           (d/q '[:find ?p :in $ % ?c :where (subclass ?c ?p)]
                                (d/db conn) rules company))))))
        (testing "term-type inherits organizational class"
          (add-term! conn "liquid-ai" "Liquid AI" :companies)
          (let [term (d/q '[:find ?e . :in $ :where [?e :term/id "liquid-ai"]]
                          (d/db conn))]
            (is (= #{startup company}
                   (set (map first
                             (d/q '[:find ?c :in $ % ?t :where (term-type ?t ?c)]
                                  (d/db conn) rules term)))))))))))

(deftest test-graph-validation
  (with-graph
    (fn [conn]
      (testing "valid graph has no errors"
        (add-term! conn "liquid-ai" "Liquid AI" :companies)
        (let [term (d/q '[:find ?e . :in $ :where [?e :term/id "liquid-ai"]]
                        (d/db conn))]
          (d/transact! conn [{:form/raw "LiquidAI" :form/term term}])
          (is (empty? (:errors (graph/validate-graph conn))))))
      (testing "terms without category and duplicate normalized forms are errors"
        (d/transact! conn [{:term/id "orphan" :term/normalized "Orphan"}])
        (add-term! conn "other-liquid" "Liquid AI" :roles)
        (let [errors (:errors (graph/validate-graph conn))
              types  (set (map :type errors))]
          (is (contains? types :term-without-category))
          (is (contains? types :duplicate-normalized))
          (is (contains? types :normalized-category-conflict)))))))

(deftest test-cache-graph-comparison
  (with-graph
    (fn [conn]
      (add-term! conn "liquid-ai" "Liquid AI" :companies)
      (let [term (d/q '[:find ?e . :in $ :where [?e :term/id "liquid-ai"]]
                      (d/db conn))]
        (d/transact! conn [{:form/raw "LiquidAI" :form/term term}])
        (testing "matching cache passes"
          (is (empty? (graph/cache-graph-errors
                       conn
                       {"LiquidAI" {:normalized "Liquid AI" :category "companies"}}))))
        (testing "mismatch and missing forms are errors"
          (is (= :cache-graph-mismatch
                 (:type (first (graph/cache-graph-errors
                                conn
                                {"LiquidAI" {:normalized "Liquid AI" :category "roles"}})))))
          (is (= :cache-missing-form
                 (:type (first (graph/cache-graph-errors
                                conn
                                {"Missing" {:normalized "Missing" :category "companies"}}))))))))))

(deftest test-class-cycle
  (testing "class hierarchy cycles are detected"
    (is (nil? (graph/class-cycle? {:a {:parents [:b]} :b {:parents [:c]}})))
    (is (= [:a :b :a]
           (graph/class-cycle? {:a {:parents [:b]} :b {:parents [:a]}})))))

(deftest test-same-as-closure
  (with-graph
    (fn [conn]
      (let [a (add-term! conn "same-a" "Same A" :companies)
            b (add-term! conn "same-b" "Same B" :companies)
            c (add-term! conn "same-c" "Same C" :companies)]
        (d/transact! conn [[:db/add a :term/same-as b]
                           [:db/add a :term/same-as c]])
        (testing "sameAs closure is symmetric and transitive"
          (is (= #{a b c} (graph/same-as-closure conn a)))
          (is (= #{a b c} (graph/same-as-closure conn b))))))))

(deftest test-broader-narrower-related-and-label-ancestors
  (with-graph
    (fn [conn]
      (let [a (add-term! conn "broad-a" "Broad A" :fields)
            b (add-term! conn "broad-b" "Broad B" :fields)
            c (add-term! conn "broad-c" "Broad C" :fields)
            rules (graph/load-rules graph/default-rules-path)]
        (d/transact! conn [{:db/id a :term/broader b}])
        (d/transact! conn [{:db/id b :term/broader c}])
        (d/transact! conn [{:db/id a :term/related b}])
        (testing "broader is transitive and narrower is its inverse"
          (is (= #{b c}
                 (set (map first
                           (d/q '[:find ?x :in $ % ?a :where (broader ?a ?x)]
                                (d/db conn) rules a)))))
          (is (= #{a b}
                 (set (map first
                           (d/q '[:find ?x :in $ % ?c :where (narrower ?c ?x)]
                                (d/db conn) rules c)))))
          (is (= #{b} (set (map first
                                (d/q '[:find ?x :in $ % ?a :where (related ?a ?x)]
                                     (d/db conn) rules a))))))
        (testing "label ancestry is transitive"
          (d/transact! conn [{:label/name "ai"}])
          (d/transact! conn [{:label/name "ai/agents"}])
          (let [ai (d/q '[:find ?e . :in $ :where [?e :label/name "ai"]] (d/db conn))
                agents (d/q '[:find ?e . :in $ :where [?e :label/name "ai/agents"]] (d/db conn))]
            (d/transact! conn [{:db/id agents :label/parent ai}])
            (is (= #{ai}
                   (set (map first
                             (d/q '[:find ?p :in $ % ?l :where (label-ancestor ?l ?p)]
                                  (d/db conn) rules agents)))))))))))
