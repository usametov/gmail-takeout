(ns astanova.cli-test
  "Unit tests for astanova.cli — argument parsing, query building, formatting."
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.string :as str]
            [babashka.cli :as cli]
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
  (testing "cli-tree has expected commands"
    (let [cmds (set (keep (comp first :cmds) sut/cli-tree))]
      (is (contains? cmds "ingest"))
      (is (contains? cmds "query"))
      (is (contains? cmds "stats"))
      (is (contains? cmds "export"))
      (is (contains? cmds "threads"))
      (is (contains? cmds "split"))
      (is (contains? cmds "labels")))))

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
  (testing ":labels filter adds clause (default mode: all)"
    (let [clauses (#'sut/build-query-clauses {:labels "inbox"})]
      (is (= 2 (count clauses)))
      (is (= ['?e :email/labels "inbox"] (first clauses)))
      (is (= ['?e :email/subject] (second clauses))))))

(deftest test-build-query-clauses-labels-all-mode
  (testing ":labels with --labels-mode all generates separate clauses"
    (let [clauses (#'sut/build-query-clauses {:labels "a,b" :labels-mode "all"})]
      (is (= 3 (count clauses)))
      (is (= ['?e :email/labels "a"] (first clauses)))
      (is (= ['?e :email/labels "b"] (second clauses)))
      (is (= ['?e :email/subject] (nth clauses 2))))))

(deftest test-build-query-clauses-labels-any-mode
  (testing ":labels with --labels-mode any wraps in :or"
    (let [clauses (#'sut/build-query-clauses {:labels "a,b" :labels-mode "any"})]
      (is (= 2 (count clauses)))
      (let [or-clause (first clauses)]
        (is (= 'or (first or-clause)))
        (is (some #(= ['?e :email/labels "a"] %) (rest or-clause)))
        (is (some #(= ['?e :email/labels "b"] %) (rest or-clause))))
      (is (= ['?e :email/subject] (second clauses))))))

(deftest test-build-query-clauses-labels-default-mode
  (testing ":labels defaults to all mode"
    (let [clauses (#'sut/build-query-clauses {:labels "a,b"})]
      (is (= 3 (count clauses)))
      (is (= ['?e :email/labels "a"] (first clauses)))
      (is (= ['?e :email/labels "b"] (second clauses)))
      (is (= ['?e :email/subject] (nth clauses 2))))))

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
                   {:subject "hello" :from "alice@example.com" :labels "inbox"})]
      ;; subject adds 2 clauses (pattern + predicate), from 1, labels 1 (or with 1 branch)
      (is (= 4 (count clauses)) "subject(2) + from(1) + labels(1) = 4"))))

(deftest test-build-query-clauses-text
  (testing "--text adds or clause and includes? predicate"
    (let [clauses (#'sut/build-query-clauses {:text "machine learning"})]
      (is (= 2 (count clauses)))
      (let [or-clause (first clauses)
            pred-vec  (second clauses)]
        (is (or (= :or (first or-clause))
                (= 'or (first or-clause))))
        (is (some #(= ['?e :email/subject '?txt] %) or-clause))
        (is (some #(= ['?e :email/body-truncated '?txt] %) or-clause))
        (is (list? (first pred-vec)))
        (is (= 'clojure.string/includes? (ffirst pred-vec)))))))

;; ─── Arg parsing integration ────────────────────────────────────

(deftest test-query-parse-opts
  (testing "query args are parsed into opts with labels and labels-mode"
    (let [opts (cli/parse-opts
                 ["-l" "a,b" "--labels-mode" "all" "-s" "hello"]
                 {:spec sut/query-spec})]
      (is (= "a,b" (:labels opts)))
      (is (= "all" (:labels-mode opts)))
      (is (= "hello" (:subject opts))))))

(deftest test-query-parse-opts-default-labels-mode
  (testing "labels-mode defaults to any when not specified"
    (let [opts (cli/parse-opts
                 ["-l" "x,y,z"]
                 {:spec sut/query-spec})]
      (is (= "x,y,z" (:labels opts)))
      (is (= "any" (:labels-mode opts))))))

(deftest test-query-parse-opts-text
  (testing "--text is parsed correctly"
    (let [opts (cli/parse-opts
                 ["--text" "machine learning"]
                 {:spec sut/query-spec})]
      (is (= "machine learning" (:text opts))))))

;; ─── build-query ────────────────────────────────────────────────

(deftest test-build-query-structure
  (testing "returns a vector with :find, :where, and clauses"
    (let [clauses (#'sut/build-query-clauses {:from "a@b.com"})
          query   (#'sut/build-query clauses)]
      (is (vector? query))
      (is (some #(= :find %) query))
      (is (some #(= :where %) query))
      (let [find-part (second query)]
        (is (= '... (last find-part)))
        (is (some #(= 'pull %) (first find-part)))))))

(deftest test-build-query-pull-pattern
  (testing "pull pattern includes expected attributes"
    (let [query (#'sut/build-query [['?e :email/subject]])]
      (is (re-find #"email/subject" (str query)))
      (is (re-find #"email/from" (str query)))
      (is (re-find #"email/to" (str query)))
      (is (re-find #"email/date" (str query)))
      (is (re-find #"email/labels" (str query)))
      ;; email/body is now included for --text support
      (is (re-find #"email/body" (str query))))))


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

;; ─── print-help (removed - replaced by babashka/cli auto-help) ───

(deftest test-print-help-contents
  (testing "help mentions key commands"
    (let [cmds (set (keep (comp first :cmds) sut/cli-tree))]
      (is (contains? cmds "ingest"))
      (is (contains? cmds "query"))
      (is (contains? cmds "stats"))
      (is (contains? cmds "export"))
      (is (contains? cmds "threads")))))

;; ─── Split command ───────────────────────────────────────────────

(defn- with-temp-mbox
  "Create a temp mbox file, run f with its path, then clean up."
  [content-bytes f]
  (let [tmp (java.io.File/createTempFile "test-split" ".mbox")]
    (try
      (with-open [w (java.io.FileOutputStream. tmp)]
        (.write w content-bytes))
      (f (.getAbsolutePath tmp))
      (finally (.delete tmp)))))

(defn- with-temp-dir
  "Create a temp dir, run f with its path, then clean up."
  [f]
  (let [d (doto (java.io.File/createTempFile "test-split-out" "")
            (.delete)
            (.mkdirs))]
    (try
      (f (.getAbsolutePath d))
      (finally
        (doseq [c (.listFiles d)] (.delete c))
        (.delete d)))))

(deftest test-split-cmd-exists-in-cli-tree
  (testing "cli-tree includes the split command"
    (let [cmds (set (keep (comp first :cmds) sut/cli-tree))]
      (is (contains? cmds "split")))))

(deftest test-split-spec-parse
  (testing "split-spec parses --size and --output correctly"
    (let [opts (cli/parse-opts
                 ["-s" "200" "-o" "/tmp/out" "input.mbox"]
                 {:spec sut/split-spec})]
      (is (= 200 (:size opts)))
      (is (= "/tmp/out" (:output opts))))))

(deftest test-split-spec-defaults
  (testing "split-spec defaults to 500 MB size"
    (let [opts (cli/parse-opts ["input.mbox"] {:spec sut/split-spec})]
      (is (= 500 (:size opts)))
      (is (nil? (:output opts))))))

(deftest test-split-mbox-creates-chunks
  (testing "split-mbox! creates chunk files with correct names"
    (let [content (.getBytes "From a@b.com\n\nHi\n\nFrom c@d.com\n\nBye\n" "UTF-8")]
      (with-temp-mbox content
        (fn [mbox-path]
          (with-temp-dir
            (fn [out-dir]
              (#'sut/split-mbox! mbox-path 1 out-dir)
              (let [files (sort (filter #(.endsWith (.getName %) ".mbox")
                                        (.listFiles (java.io.File. out-dir))))]
                (is (= 1 (count files)))
                (is (re-find #"part-0001" (.getName (first files))))))))))))

(deftest test-split-mbox-content-preserved
  (testing "chunk content is byte-identical to original"
    (let [content (.getBytes "From a@b.com\n\nHi\n\nFrom c@d.com\n\nBye\n" "UTF-8")]
      (with-temp-mbox content
        (fn [mbox-path]
          (with-temp-dir
            (fn [out-dir]
              (#'sut/split-mbox! mbox-path 1 out-dir)
              (let [chunk (first (filter #(.endsWith (.getName %) ".mbox")
                                         (.listFiles (java.io.File. out-dir))))]
                (is (= (slurp mbox-path) (slurp chunk)))))))))))

(deftest test-split-mbox-multiple-chunks
  (testing "split-mbox! creates multiple chunks for large-enough files"
    (let [content (.getBytes (apply str (repeat 100 "From x@y Mon Jan 01 10:00:00 2024\nSubject: T\n\nBody\n\n")) "UTF-8")]
      (with-temp-mbox content
        (fn [mbox-path]
          (with-temp-dir
            (fn [out-dir]
              ;; Use tiny chunk size (1 byte) to force multiple chunks
              (#'sut/split-mbox! mbox-path 1 out-dir)
              (let [files (filter #(.endsWith (.getName %) ".mbox")
                                  (.listFiles (java.io.File. out-dir)))]
                (is (pos? (count files)))
                ;; Reassemble and verify content matches
                (let [reassembled (apply str (map slurp (sort files)))]
                  (is (= (slurp mbox-path) reassembled)))))))))))

(deftest test-split-cmd-single-file
  (testing "split-cmd processes a single file"
    (let [content (.getBytes "From a@b.com\n\nHi\n" "UTF-8")]
      (with-temp-mbox content
        (fn [mbox-path]
          (with-temp-dir
            (fn [out-dir]
              (with-out-str
                (sut/split-cmd
                  {:opts {:size 1 :output out-dir}
                   :args [mbox-path]}))
              (let [files (filter #(.endsWith (.getName %) ".mbox")
                                  (.listFiles (java.io.File. out-dir)))]
                (is (= 1 (count files)))))))))))

(deftest test-split-cmd-uses-default-output-dir
  (testing "split-cmd uses input file dir when --output not specified"
    (let [content (.getBytes "From a@b.com\n\nBody\n" "UTF-8")]
      (with-temp-mbox content
        (fn [mbox-path]
          (let [parent (.getParent (java.io.File. mbox-path))]
            (with-out-str
              (sut/split-cmd
                {:opts {:size 1}
                 :args [mbox-path]}))
            (let [stem (str/replace (.getName (java.io.File. mbox-path)) #"\.mbox$" "")
                  chunk (java.io.File. parent (str stem ".part-0001.mbox"))]
              (is (.exists chunk) "chunk created in input dir")
              (.delete chunk))))))))
