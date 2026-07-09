(ns astanova.cli-test
  "Unit tests for astanova.cli — argument parsing, query building, formatting."
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.string :as str]
            [astanova.cli :as sut])
  (:import [java.util Date]
           [java.time Instant]))

;; ─── Helpers ─────────────────────────────────────────────────────

(defn- capture-out
  "Run thunk, return string written to *out*."
  [thunk]
  (with-out-str (thunk)))

;; ─── Commands map ───────────────────────────────────────────────

(deftest test-commands-map
  (testing "commands map has expected entries"
    (is (contains? sut/commands "ingest"))
    (is (contains? sut/commands "query"))
    (is (contains? sut/commands "stats"))
    (is (contains? sut/commands "export")))
  (testing "each command has :fn (as a Var) and :doc keys"
    (doseq [[name info] sut/commands]
      (is (instance? clojure.lang.Var (:fn info)) (str name " :fn is a Var"))
      (is (string? (:doc info)) (str name " :doc is a string")))))

;; ─── parse-date ─────────────────────────────────────────────────

(deftest test-parse-date
  (testing "parses ISO date-time (UTC)"
    (let [d (#'sut/parse-date "2024-06-15T10:30:00Z")]
      (is (instance? Date d))
      (is (= 1718447400000 (.getTime d)))))
  (testing "parses ISO date (local date -> start of day UTC)"
    (let [d (#'sut/parse-date "2024-01-01")]
      (is (instance? Date d))))
  (testing "returns a Date for valid inputs"
    (doseq [s ["2023-12-31" "2024-06-15T10:30:00Z" "2025-01-01T00:00:00Z"]]
      (is (instance? Date (#'sut/parse-date s)) (str "input: " s))))
  ;; Note: invalid inputs are handled via System/exit, so testing that path
  ;; would kill the JVM. The guard clause is straightforward and well-understood.
  )

;; ─── build-query-clauses ────────────────────────────────────────

(deftest test-build-query-clauses-default
  (testing "no filters returns a single subject pattern (catch-all)"
    (let [clauses (#'sut/build-query-clauses {})]
      (is (= 1 (count clauses)))
      (is (= ['?e :email/subject] (first clauses))))))

(deftest test-build-query-clauses-subject
  (testing ":subject filter adds includes? predicate"
    (let [clauses (#'sut/build-query-clauses {:subject "hello"})]
      (is (= 2 (count clauses)))
      (is (= ['?e :email/subject '?s] (nth clauses 0)))
      (let [pred-vec (nth clauses 1)
            pred     (first pred-vec)]
        (is (vector? pred-vec) "should be wrapped in a vector")
        (is (list? pred) "the inner form should be a list")
        (is (= 'clojure.string/includes? (first pred)))
        (is (= '?s (second pred)))
        (is (= "hello" (nth pred 2)))))))

(deftest test-build-query-clauses-from
  (testing ":from filter adds equality clause"
    (let [clauses (#'sut/build-query-clauses {:from "alice@example.com"})]
      (is (= 1 (count clauses)))
      (is (= ['?e :email/from "alice@example.com"] (first clauses))))))

(deftest test-build-query-clauses-to
  (testing ":to filter adds equality clause"
    (let [clauses (#'sut/build-query-clauses {:to "bob@example.com"})]
      (is (= 1 (count clauses)))
      (is (= ['?e :email/to "bob@example.com"] (first clauses))))))

(deftest test-build-query-clauses-label
  (testing ":labels filter adds or clause with parsed labels"
    (let [clauses (#'sut/build-query-clauses {:labels ["inbox"]})]
      (is (= 1 (count clauses)))
      (let [first-clause (first clauses)]
        ;; labels-any wraps in [:or ...]
        (is (or (= 'or (first first-clause))
                (= :or (first first-clause))))
        (is (some #(= ['?e :email/labels "inbox"] %) first-clause))))))

(deftest test-build-query-clauses-since
  (testing ":since filter adds date >= predicate"
    (let [clauses (#'sut/build-query-clauses {:since "2024-01-01"})]
      (is (= 2 (count clauses)))
      (is (= ['?e :email/date '?d] (nth clauses 0)))
      (let [pred-vec (nth clauses 1)
            pred     (first pred-vec)]
        (is (vector? pred-vec) "should be wrapped in a vector")
        (is (list? pred) "the inner form should be a list")
        (is (= '>= (first pred)))
        (is (= '?d (second pred)))))))

(deftest test-build-query-clauses-before
  (testing ":before filter adds date < predicate"
    (let [clauses (#'sut/build-query-clauses {:before "2024-06-01"})]
      (is (= 2 (count clauses)))
      (is (= ['?e :email/date '?d] (nth clauses 0)))
      (let [pred-vec (nth clauses 1)
            pred     (first pred-vec)]
        (is (vector? pred-vec) "should be wrapped in a vector")
        (is (list? pred) "the inner form should be a list")
        (is (= '< (first pred)))
        (is (= '?d (second pred)))))))

(deftest test-build-query-clauses-multiple-filters
  (testing "multiple filters are combined"
    (let [clauses (#'sut/build-query-clauses
                   {:subject "hello" :from "alice@example.com" :labels ["inbox"]})]
      ;; subject adds 2 clauses (pattern + predicate), from 1, labels 1 (or with 1 branch)
      (is (= 4 (count clauses)) "subject(2) + from(1) + labels(1) = 4"))))

;; ─── build-query ────────────────────────────────────────────────

(deftest test-build-query-structure
  (testing "returns a vector with :find, :where, and clauses"
    (let [clauses (#'sut/build-query-clauses {:from "a@b.com"})
          query   (#'sut/build-query clauses 10 0)]
      (is (vector? query))
      (is (some #(= :find %) query))
      (is (some #(= :where %) query))
      (let [find-part (second query)]
        (is (= '... (last find-part)))
        (is (some #(= 'pull %) (first find-part)))))))

(deftest test-build-query-pull-pattern
  (testing "pull pattern includes expected attributes"
    (let [query (#'sut/build-query [['?e :email/subject]] 5 0)]
      (is (re-find #"email/subject" (str query)))
      (is (re-find #"email/from" (str query)))
      (is (re-find #"email/to" (str query)))
      (is (re-find #"email/date" (str query)))
      (is (re-find #"email/labels" (str query)))
      ;; email/body is now included for --text support
      (is (re-find #"email/body" (str query))))))

;; ─── json-escape ────────────────────────────────────────────────

(deftest test-json-escape
  (testing "plain strings pass through (escaped)"
    (is (= "hello" (#'sut/json-escape "hello"))))
  (testing "double quotes are escaped"
    (is (= "say \\\"hi\\\"" (#'sut/json-escape "say \"hi\""))))
  (testing "backslashes are escaped"
    (is (= "a\\\\b" (#'sut/json-escape "a\\b"))))
  (testing "newlines are escaped"
    (is (= "line1\\nline2" (#'sut/json-escape "line1\nline2"))))
  (testing "tabs are escaped"
    (is (= "col1\\tcol2" (#'sut/json-escape "col1\tcol2"))))
  (testing "handles non-string input (calls str)"
    (is (= "42" (#'sut/json-escape 42)))))

;; ─── json-val ───────────────────────────────────────────────────

(deftest test-json-val-string
  (testing "strings are quoted and escaped"
    (is (= "\"hello\"" (#'sut/json-val "hello")))
    (is (= "\"say \\\"hi\\\"\"" (#'sut/json-val "say \"hi\"")))))

(deftest test-json-val-nil
  (testing "nil becomes null"
    (is (= "null" (#'sut/json-val nil)))))

(deftest test-json-val-number
  (testing "numbers are stringified directly"
    (is (= "42" (#'sut/json-val 42)))
    (is (= "3.14" (#'sut/json-val 3.14)))))

(deftest test-json-val-keyword
  (testing "keywords use their name (unqualified)"
    (is (= "\"inbox\"" (#'sut/json-val :inbox)))
    (is (= "\"important\"" (#'sut/json-val :important)))))

(deftest test-json-val-collection
  (testing "vectors become JSON arrays"
    (is (= "[\"a\",\"b\"]" (#'sut/json-val ["a" "b"]))))
  (testing "nested values in collections"
    (is (= "[1,2,3]" (#'sut/json-val [1 2 3])))))

(deftest test-json-val-date
  (testing "java.util.Date becomes ISO instant string"
    (let [d    (Date/from (Instant/parse "2024-06-15T10:30:00Z"))
          json (#'sut/json-val d)]
      (is (re-find #"2024-06-15T10:30:00Z" json))
      (is (.startsWith json "\""))
      (is (.endsWith json "\"")))))

(deftest test-json-val-other
  (testing "other types are stringified and quoted"
    (is (= "\"true\"" (#'sut/json-val true)))
    (is (= "\"false\"" (#'sut/json-val false)))))

;; ─── to-json ────────────────────────────────────────────────────

(deftest test-to-json-empty
  (testing "empty results produce an empty JSON array"
    (is (= "[\n  \n]" (#'sut/to-json [])))))

(deftest test-to-json-single
  (testing "single result produces a JSON object in an array"
    (let [json (#'sut/to-json [{:email/subject "Hello" :email/from "a@b.com"}])]
      ;; name strips namespace from keywords: :email/subject -> "subject"
      (is (re-find #"\"subject\": \"Hello\"" json))
      (is (re-find #"\"from\": \"a@b.com\"" json))
      (is (.startsWith json "["))
      (is (.endsWith json "]")))))

(deftest test-to-json-multiple
  (testing "multiple results separated by commas"
    (let [json (#'sut/to-json [{:email/subject "A"} {:email/subject "B"}])]
      (is (re-find #"A" json))
      (is (re-find #"B" json))
      ;; name strips namespace, so keyword :email/subject becomes "subject"
      (is (= 2 (count (re-seq #"\"subject\"" json)))))))

;; ─── print-table ────────────────────────────────────────────────

(deftest test-print-table-header
  (testing "header includes expected column names (name strips namespace)"
    (let [rows    [{:email/subject "S" :email/from "F" :email/date (Date.) :email/labels ["in"]}]
          output  (capture-out #(#'sut/print-table rows))]
      (is (re-find #"subject" output))
      (is (re-find #"from" output))
      (is (re-find #"date" output))
      (is (re-find #"labels" output)))))

(deftest test-print-table-data
  (testing "data is rendered in cells"
    (let [rows    [{:email/subject "Test Subject"
                    :email/from "alice@example.com"
                    :email/date (Date.)
                    :email/labels ["inbox" "important"]}]
          output  (capture-out #(#'sut/print-table rows))]
      (is (re-find #"Test Subject" output))
      (is (re-find #"alice@example.com" output)))))

;; ─── print-help ─────────────────────────────────────────────────

(deftest test-print-help-contents
  (testing "help mentions key sections"
    (let [output (capture-out #(#'sut/print-help))]
      (is (re-find #"takeout" output))
      (is (re-find #"ingest" output))
      (is (re-find #"query" output))
      (is (re-find #"stats" output))
      (is (re-find #"export" output))
      (is (re-find #"global-options" output))
      (is (re-find #"--db" output)))))
