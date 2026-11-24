(ns astanova.regression-test
  "Regression tests for the parser, splitter and CLI fixes made during the
   ingest-correctness pass."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [astanova.cli :as cli]
            [astanova.db :as db]
            [astanova.ingest :as ingest]
            [astanova.label :as label]
            [astanova.mbox-splitter :as mbox]
            [astanova.parse :as parse])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ─── Test helpers ────────────────────────────────────────────────

(defn- delete-tree! [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [f (reverse (file-seq root))]
        (io/delete-file f true)))))

(defn- with-temp-dir [f]
  (let [dir (Files/createTempDirectory "takeout-regression"
                                       (make-array FileAttribute 0))]
    (try
      (f (str dir))
      (finally
        (delete-tree! (str dir))))))

(defn- write-file! [dir name content]
  (let [f (io/file dir name)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- read-bytes [f]
  (Files/readAllBytes (.toPath (io/file f))))

(defn- concat-bytes [chunks]
  (let [out (ByteArrayOutputStream.)]
    (doseq [chunk chunks]
      (.write out ^bytes chunk))
    (.toByteArray out)))

(defn- entity [db-path message-id]
  (let [conn (db/create-conn db-path)]
    (try
      (when-let [e (d/entity (d/db conn) [:email/id message-id])]
        (into {} e))
      (finally (db/close-conn conn)))))

;; ─── Parser: thread IDs and Gmail IDs ───────────────────────────

(defn- raw-msg [headers body]
  (str (str/join "\n" headers) "\n\n" body))

(deftest test-thread-id-prefers-x-gm-thrid
  (testing "X-GM-THRID wins over References and Thread-Topic"
    (let [parsed (parse/parse-raw-message
                  (raw-msg ["From: a@b.com"
                            "Subject: Re: test"
                            "Message-ID: <thread-priority@test>"
                            "Thread-Topic: Some topic"
                            "References: <root-a@test> <root-b@test>"
                            "X-GM-THRID: 18446744073709551615"]
                           "Body"))]
      (is (= "18446744073709551615" (:thread-id parsed))))))

(deftest test-thread-id-references-uses-first-id
  (testing "References fallback groups on the thread root"
    (let [parsed (parse/parse-raw-message
                  (raw-msg ["From: a@b.com"
                            "Subject: Re: test"
                            "Message-ID: <thread-ref@test>"
                            "References: <root-a@test> <root-b@test>"]
                           "Body"))]
      (is (= "<root-a@test>" (:thread-id parsed))))))

(deftest test-thread-id-thread-topic-is-last-resort
  (testing "Thread-Topic is only used when no thread id header exists"
    (let [parsed (parse/parse-raw-message
                  (raw-msg ["From: a@b.com"
                            "Subject: test"
                            "Message-ID: <thread-topic@test>"
                            "Thread-Topic: Topic only"]
                           "Body"))]
      (is (= "Topic only" (:thread-id parsed))))))

(deftest test-gmail-id-validation
  (let [raw (str "From: a@b.com\nSubject: x\nMessage-ID: <gid@test>\n\nBody")]
    (testing "numeric Gmail envelopes"
      (is (= "123" (:gmail-id (parse/parse-raw-message
                               {:from-line "From 123@xxx Mon Jan 01 10:00:00 2024"
                                :raw-message raw}))))
      (is (= "123" (:gmail-id (parse/parse-raw-message
                               {:from-line "From <123@xxx> Mon Jan 01 10:00:00 2024"
                                :raw-message raw})))))
    (testing "standard mbox envelopes do not become Gmail IDs"
      (is (nil? (:gmail-id (parse/parse-raw-message
                            {:from-line "From alice@example.com Mon Jan 01 10:00:00 2024"
                             :raw-message raw})))))
    (testing "malformed envelopes do not become Gmail IDs"
      (is (nil? (:gmail-id (parse/parse-raw-message
                            {:from-line "From MAILER-DAEMON Mon Jan 01 10:00:00 2024"
                             :raw-message raw})))))))

;; ─── Parser: body selection ─────────────────────────────────────

(deftest test-empty-text-part-falls-back-to-html
  (testing "an empty text/plain alternative does not shadow the HTML body"
    (let [raw (str "From: a@b.com\n"
                   "Subject: Empty plain alternative\n"
                   "Message-ID: <empty-plain@test>\n"
                   "MIME-Version: 1.0\n"
                   "Content-Type: multipart/alternative; boundary=outer\n"
                   "\n"
                   "--outer\n"
                   "Content-Type: text/plain; charset=utf-8\n"
                   "\n"
                   "\n"
                   "--outer\n"
                   "Content-Type: text/html; charset=utf-8\n"
                   "\n"
                   "<p>Hello from HTML</p>\n"
                   "--outer--\n")
          parsed (parse/parse-raw-message raw)]
      (is (= "Hello from HTML" (:body parsed)))
      (is (str/includes? (:html parsed) "Hello from HTML")))))

;; ─── Parser: From_ line / message alignment ────────────────────

(deftest test-body-line-starting-with-from-does-not-shift-gmail-ids
  (testing "prose lines beginning with 'From ' are not treated as separators"
    (with-temp-dir
      (fn [dir]
        (let [mbox-file (write-file!
                         dir "misalign.mbox"
                         (str "From 111@xxx Mon Jan 01 10:00:00 2024\n"
                              "From: a@b.com\n"
                              "Subject: First\n"
                              "Message-ID: <misalign-1@test>\n"
                              "Date: Mon, 1 Jan 2024 10:00:00 +0000\n"
                              "\n"
                              "Hello\n"
                              "From now on, 2024 will be better.\n"
                              "\n"
                              "From 222@xxx Tue Jan 02 10:00:00 2024\n"
                              "From: c@d.com\n"
                              "Subject: Second\n"
                              "Message-ID: <misalign-2@test>\n"
                              "Date: Tue, 2 Jan 2024 10:00:00 +0000\n"
                              "\n"
                              "Body two\n"))
              emails (doall (parse/parse-mbox (.getAbsolutePath mbox-file)))]
          (is (= 2 (count emails)))
          (is (= ["111" "222"] (mapv :gmail-id emails)))
          (is (= ["First" "Second"] (mapv :subject emails))))))))

(deftest test-standard-mbox-ingests-without-gmail-id
  (testing "non-Gmail envelopes are allowed and :email/gmail-id is omitted"
    (with-temp-dir
      (fn [dir]
        (let [mbox-file (write-file!
                         dir "standard.mbox"
                         (str "From alice@example.com Mon Jan 01 10:00:00 2024\n"
                              "From: alice@example.com\n"
                              "To: bob@example.com\n"
                              "Subject: Standard mbox\n"
                              "Message-ID: <standard@test>\n"
                              "Date: Mon, 1 Jan 2024 10:00:00 +0000\n"
                              "\n"
                              "Standard mbox body\n"))
              db-path (str dir "/standard.db")]
          (let [conn (db/create-conn db-path)]
            (try
              (let [stats (ingest/ingest-mbox! conn (.getAbsolutePath mbox-file) "test")]
                (is (= 1 (:email-count stats))))
              (finally (db/close-conn conn))))
          (let [e (entity db-path "<standard@test>")]
            (is (some? e))
            (is (nil? (:email/gmail-id e)))
            (is (= "Standard mbox body" (:email/body-truncated e)))))))))

(deftest test-email-map-omits-nil-single-valued-attributes
  (testing "nil gmail-id/thread-id are not emitted into transactions"
    (let [e (ingest/email-map->entity {:message-id "<nil-attrs@test>"
                                       :body nil
                                       :from "a@b.com"}
                                      "f.mbox" "test")]
      (is (not (contains? e :email/gmail-id)))
      (is (not (contains? e :email/thread-id)))
      (is (= "" (:email/body-truncated e)))
      (is (zero? (:email/body-length e))))))

;; ─── Splitter ────────────────────────────────────────────────────

(deftest test-is-from-start-respects-buffer-context
  (testing "a 'From ' match at a non-line-start is rejected"
    (is (false? (mbox/is-from-start? "xFrom a@b.com\n" 1 \x))))
  (testing "the byte before a buffer start is respected at index 0"
    (is (false? (mbox/is-from-start? "From a@b.com\n" 0 \x)))
    (is (true? (mbox/is-from-start? "From a@b.com\n" 0 \newline)))))

(deftest test-split-mbox-utf8-boundaries
  (testing "splitting after multibyte text keeps byte-exact message boundaries"
    (with-temp-dir
      (fn [dir]
        (let [content (str "From 1@xxx Mon Jan 01 10:00:00 2024\n"
                           "From: a@b.com\n"
                           "Subject: First\n"
                           "Message-ID: <utf8-1@test>\n"
                           "Date: Mon, 1 Jan 2024 10:00:00 +0000\n"
                           "\n"
                           (apply str (repeat 600000 "é"))
                           "\n"
                           "From 2@xxx Tue Jan 02 10:00:00 2024\n"
                           "From: c@d.com\n"
                           "Subject: Second\n"
                           "Message-ID: <utf8-2@test>\n"
                           "Date: Tue, 2 Jan 2024 10:00:00 +0000\n"
                           "\n"
                           "Body two\n")
              mbox-file (write-file! dir "utf8.mbox" content)
              out-dir (str dir "/chunks")]
          (with-out-str
            (mbox/split-mbox! (.getAbsolutePath mbox-file) 1 out-dir))
          (let [chunks (->> (.listFiles (io/file out-dir))
                            (filter #(str/ends-with? (.getName %) ".mbox"))
                            (sort-by #(.getName %))
                            vec)
                bytes  (mapv read-bytes chunks)]
            (is (>= (count chunks) 2))
            (is (every? #(= "From " (String. (byte-array (take 5 %)) "ISO-8859-1"))
                        (rest bytes)))
            (is (= 10 (bit-and 0xff (aget (first bytes) (dec (alength (first bytes)))))))
            (is (java.util.Arrays/equals (read-bytes mbox-file)
                                         (concat-bytes bytes)))
            (is (= 2 (mbox/count-mbox-messages (.getAbsolutePath mbox-file))))))))))

;; ─── CLI / database visibility and stats ───────────────────────

(deftest test-subjectless-email-counts-and-queries
  (testing "emails without a Subject remain visible to default queries and stats"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/no-subject.db")
              conn    (db/create-conn db-path)]
          (try
            (d/transact! conn [{:email/id "<no-subject@test>"
                                :email/from "a@b.com"
                                :email/date #inst "2024-01-01T00:00:00.000-00:00"
                                :email/thread-id "thread-no-subject"
                                :email/body-truncated "body"
                                :email/body-length 4}])
            (let [snap (d/db conn)
                  query (#'cli/build-query (#'cli/build-query-clauses {}))]
              (is (= 1 (db/count-emails snap)))
              (is (= 1 (count (d/q query snap)))))
            (finally (db/close-conn conn)))
          (let [stats (with-out-str (#'cli/stats-cmd {:opts {:db db-path}}))]
            (is (re-find #"Total emails:\s+1" stats))
            (is (re-find #"Total threads:\s+1" stats))))))))

(deftest test-empty-db-stats-and-inspect-thread
  (testing "empty databases produce zero counts instead of nil/NPE"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/empty.db")
              stats   (with-out-str (#'cli/stats-cmd {:opts {:db db-path}}))
              conn    (db/create-conn db-path)]
          (is (re-find #"Total emails:\s+0" stats))
          (is (re-find #"Total threads:\s+0" stats))
          (try
            (let [inspected (label/inspect-thread-labels (d/db conn) "missing-thread")]
              (is (= 0 (:email-count inspected)))
              (is (= [] (:all-labels inspected))))
            (finally (db/close-conn conn))))))))

;; ─── CLI update commands tolerate missing optional values ───────

(deftest test-update-message-ids-without-thread-id
  (testing "a mapping without :thread-id updates only the Gmail ID"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/ids.db")
              conn    (db/create-conn db-path)]
          (d/transact! conn [{:email/id "<ids@test>"
                              :email/subject "IDs"
                              :email/body-truncated "body"
                              :email/body-length 4}])
          (db/close-conn conn)
          (let [mapping (write-file! dir "map.edn"
                                     (pr-str {"<ids@test>" {:gmail-id "gmail-1"}}))]
            (with-out-str
              (#'cli/update-msg-ids-cmd {:opts {:db db-path :from (.getAbsolutePath mapping)}}))
            (let [e (entity db-path "<ids@test>")]
              (is (= "gmail-1" (:email/gmail-id e)))
              (is (nil? (:email/thread-id e))))))))))

(deftest test-update-bodies-preserves-full-length
  (testing "body-length is measured before truncation"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/bodies.db")
              conn    (db/create-conn db-path)]
          (d/transact! conn [{:email/id "<body@test>"
                              :email/subject "Body"
                              :email/body-truncated "old"
                              :email/body-length 3}])
          (db/close-conn conn)
          (let [full-body (apply str (repeat 10001 "a"))
                mapping   (write-file! dir "bodies.edn"
                                       (pr-str {"<body@test>" {:body full-body
                                                               :gmail-id "gmail-1"}}))]
            (with-out-str
              (#'cli/update-bodies-cmd {:opts {:db db-path :from (.getAbsolutePath mapping)}}))
            (let [e (entity db-path "<body@test>")]
              (is (= 10001 (:email/body-length e)))
              (is (= 10000 (count (:email/body-truncated e)))))))))))

(deftest test-upsert-content-without-email-labels
  (testing "content can be stored when the source email has no labels"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/content.db")
              conn    (db/create-conn db-path)]
          (d/transact! conn [{:email/id "<content@test>"
                              :email/subject "Content"
                              :email/body-truncated "body"
                              :email/body-length 4}])
          (db/close-conn conn)
          (let [mapping (write-file!
                         dir "content.edn"
                         (pr-str {"<content@test>"
                                  {:links {"https://example.com/paper"
                                           {:type :arxiv :xml "<entry/>"}}}}))]
            (with-out-str
              (#'cli/upsert-content-cmd {:opts {:db db-path :from (.getAbsolutePath mapping)}}))
            (let [conn (db/create-conn db-path)]
              (try
                (let [rows (d/q '[:find [(pull ?c [*]) ...]
                                  :where [?c :content/source-email "<content@test>"]]
                                (d/db conn))]
                  (is (= 1 (count rows)))
                  (is (= :paper (:content/type (first rows))))
                  (is (nil? (:content/labels (first rows)))))
                (finally (db/close-conn conn))))))))))

(deftest test-export-with-single-and-multiple-labels
  (testing "export label filters build valid Datalevin queries"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/export.db")
              conn    (db/create-conn db-path)]
          (try
            (d/transact! conn [{:email/id "<export-1@test>"
                                :email/subject "One"
                                :email/labels ["Important" "IBM"]
                                :email/body-truncated "body"
                                :email/body-length 4}
                               {:email/id "<export-2@test>"
                                :email/subject "Two"
                                :email/labels ["Important"]
                                :email/body-truncated "body"
                                :email/body-length 4}])
            (finally (db/close-conn conn)))
          (let [one (str dir "/one.edn")
                many (str dir "/many.edn")]
            (with-out-str
              (#'cli/export-cmd {:opts {:db db-path :label "Important" :format "edn"}
                                 :args [one]}))
            (with-out-str
              (#'cli/export-cmd {:opts {:db db-path :label "IBM,Important" :format "edn"}
                                 :args [many]}))
            (is (= 2 (count (read-string (slurp one)))))
            (is (= 2 (count (read-string (slurp many)))))))))))

(deftest test-debug-mime-tree-recurses
  (testing "--debug prints the complete MIME tree, not only the root"
    (let [raw (str "From: a@b.com\n"
                   "Subject: Debug tree\n"
                   "Message-ID: <debug-tree@test>\n"
                   "MIME-Version: 1.0\n"
                   "Content-Type: multipart/alternative; boundary=dbg\n"
                   "\n"
                   "--dbg\n"
                   "Content-Type: text/plain; charset=utf-8\n"
                   "\n"
                   "Plain body\n"
                   "--dbg\n"
                   "Content-Type: text/html; charset=utf-8\n"
                   "\n"
                   "<p>HTML body</p>\n"
                   "--dbg--\n")
          err (java.io.StringWriter.)]
      (binding [parse/*debug* true
                *err* err]
        (parse/parse-raw-message raw))
      (let [out (str err)]
        (is (str/includes? out "multipart/alternative"))
        (is (str/includes? out "text/plain"))
        (is (str/includes? out "text/html"))))))

(deftest test-subject-filter-is-case-insensitive-at-query-time
  (testing "the generated Datalog predicate matches mixed-case subjects"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/subject.db")
              conn    (db/create-conn db-path)]
          (try
            (d/transact! conn [{:email/id "<subject@test>"
                                :email/subject "Meeting Notes"
                                :email/body-truncated "body"
                                :email/body-length 4}])
            (finally (db/close-conn conn)))
          (let [conn (db/create-conn db-path)]
            (try
              (let [snap  (d/db conn)
                    query (#'cli/build-query
                           (#'cli/build-query-clauses {:subject "meeting"}))]
                (is (= 1 (count (d/q query snap)))))
              (finally (db/close-conn conn)))))))))

(deftest test-thread-basic-projection-omits-bodies
  (testing "thread metadata queries do not pull bodies"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/threads.db")
              conn    (db/create-conn db-path)]
          (try
            (d/transact! conn [{:email/id "<thread-basic@test>"
                                :email/subject "Thread"
                                :email/from "a@b.com"
                                :email/to ["me@example.com"]
                                :email/date #inst "2024-01-01T00:00:00.000-00:00"
                                :email/thread-id "thread-basic"
                                :email/body-truncated "a long body"
                                :email/body-length 11}
                               {:email/id "<thread-basic-2@test>"
                                :email/subject "Thread"
                                :email/from "a@b.com"
                                :email/to ["me@example.com"]
                                :email/date #inst "2024-01-01T00:00:00.000-00:00"
                                :email/thread-id "thread-basic"
                                :email/body-truncated "another body"
                                :email/body-length 12}])
            (let [snap (d/db conn)
                  emails (db/get-thread-emails-basic snap "thread-basic")
                  summary (db/get-thread-summary snap "thread-basic")]
              ;; Identical metadata must not collapse two distinct emails.
              (is (= 2 (count emails)))
              (is (not (contains? (first emails) :email/body-truncated)))
              (is (= #{"a@b.com" "me@example.com"} (:participants summary)))
              (is (= 2 (:email-count summary))))
            (finally (db/close-conn conn))))))))

(deftest test-json-cli-output-shapes
  (testing "labels/addresses/linked-labels JSON matches the documented shape"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/json.db")
              conn    (db/create-conn db-path)]
          (try
            (d/transact! conn [{:email/id "<json@test>"
                                :email/subject "JSON"
                                :email/from "alice@example.com"
                                :email/to ["bob@example.com"]
                                :email/labels ["alpha" "beta"]
                                :email/date #inst "2024-01-01T00:00:00.000-00:00"
                                :email/thread-id "json-thread"
                                :email/body-truncated "body"
                                :email/body-length 4}])
            (finally (db/close-conn conn)))
          (let [labels-out (with-out-str
                             (#'cli/labels-cmd {:opts {:db db-path :format "json"}}))
                addrs-out  (with-out-str
                             (#'cli/addresses-cmd {:opts {:db db-path :format "json"}}))
                linked-out (with-out-str
                             (#'cli/linked-labels-cmd
                              {:opts {:db db-path :label "alpha" :format "json"}}))
                labels-json (json/parse-string labels-out true)
                addrs-json  (json/parse-string addrs-out true)
                linked-json (json/parse-string linked-out true)]
            (is (= 2 (:count labels-json)))
            (is (= ["alpha" "beta"] (:labels labels-json)))
            (is (= 2 (:count addrs-json)))
            (is (= #{"alice@example.com" "bob@example.com"}
                   (set (:addresses addrs-json))))
            (is (= "alpha" (:label linked-json)))
            (is (= [["beta" 1]] (:linked linked-json)))))))))

(deftest test-threads-and-propagate-json-formats
  (testing "threads and propagate honor --format json"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/thread-json.db")
              conn    (db/create-conn db-path)]
          (try
            (d/transact! conn [{:email/id "<tj@test>"
                                :email/subject "Thread JSON"
                                :email/from "a@b.com"
                                :email/date #inst "2024-01-01T00:00:00.000-00:00"
                                :email/thread-id "tj-thread"
                                :email/labels ["alpha"]
                                :email/body-truncated "body"
                                :email/body-length 4}])
            (finally (db/close-conn conn)))
          (let [threads-out (with-out-str
                              (#'cli/threads-cmd {:opts {:db db-path :format "json"}}))
                threads-json (json/parse-string threads-out true)
                prop-out (with-out-str
                           (#'cli/propagate-cmd {:opts {:db db-path :label "alpha"
                                                        :format "json" :dry-run true}}))
                prop-json (json/parse-string prop-out true)]
            (is (= 1 (count threads-json)))
            (is (= "Thread JSON" (:subject (first threads-json))))
            (is (= 1 (:threads prop-json)))
            (is (= 0 (:updated prop-json)))))))))

(deftest test-update-links-and-connection-cleanup
  (testing "update-links updates links and closes its connection"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/links.db")
              conn    (db/create-conn db-path)]
          (d/transact! conn [{:email/id "<links@test>"
                              :email/subject "Links"
                              :email/body-truncated "body"
                              :email/body-length 4}])
          (db/close-conn conn)
          (let [mapping (write-file! dir "links.edn"
                                     (pr-str {"<links@test>"
                                              ["https://example.com/a"]}))]
            (with-out-str
              (#'cli/update-links-cmd
               {:opts {:db db-path :from (.getAbsolutePath mapping)}}))
            (let [e (entity db-path "<links@test>")]
              (is (= ["https://example.com/a"] (vec (:email/links e)))))))))))

(deftest test-fallback-id-is-timezone-independent
  (testing "fallback IDs hash epoch millis, not locale-formatted dates"
    (let [date (java.util.Date. 1700000000000)
          original (java.util.TimeZone/getDefault)]
      (try
        (let [id1 (#'parse/generate-fallback-id "subject" "from@example.com" date "body")]
          (java.util.TimeZone/setDefault
           (java.util.TimeZone/getTimeZone "Pacific/Kiritimati"))
          (is (= id1 (#'parse/generate-fallback-id
                      "subject" "from@example.com" date "body"))))
        (finally
          (java.util.TimeZone/setDefault original))))))

(deftest test-extract-urls-keeps-stdout-machine-readable
  (testing "diagnostics go to stderr, EDN stays parseable on stdout"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str dir "/urls.db")
              conn    (db/create-conn db-path)]
          (try
            (d/transact! conn [{:email/id "<urls@test>"
                                :email/subject "URLs"
                                :email/labels ["links"]
                                :email/body-truncated "see https://example.com/a"
                                :email/body-length 27}])
            (finally (db/close-conn conn)))
          (let [out (java.io.StringWriter.)
                err (java.io.StringWriter.)]
            (binding [*out* out *err* err]
              (#'cli/extract-urls-cmd
               {:opts {:db db-path :label "links" :format "edn"}
                :args []}))
            (is (= {"<urls@test>" ["https://example.com/a"]}
                   (edn/read-string (str out))))
            (is (str/includes? (str err) "emails with URLs"))))))))
