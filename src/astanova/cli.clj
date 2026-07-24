(ns astanova.cli
  "Command-line interface: argument parsing, dispatch, formatting.
   Uses babashka/cli for option parsing and command dispatch."
  (:require [astanova.db :as db]
            [astanova.ingest :as ingest]
            [astanova.label :as label]
            [astanova.parse :as parse]
            [astanova.content-processor :as cp]
            [astanova.mbox-splitter :as mbox]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [datalevin.core :as d])
  (:import [java.time Instant LocalDate ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(declare build-query-clauses build-query print-table apply-limit-offset paginated-results)

;; ─── Date parsing ───────────────────────────────────────────────

(defn- parse-date [s]
  (try
    (let [dt (ZonedDateTime/parse s DateTimeFormatter/ISO_DATE_TIME)]
      (java.util.Date/from (.toInstant dt)))
    (catch DateTimeParseException _
      (try
        (let [d (LocalDate/parse s DateTimeFormatter/ISO_LOCAL_DATE)]
          (java.util.Date/from (.toInstant (.atStartOfDay d (ZoneId/of "UTC")))))
        (catch DateTimeParseException _
          (println (str "Invalid date: " s ". Use format YYYY-MM-DD."))
          (System/exit 1))))))

;; ─── Specs ──────────────────────────────────────────────────────

(def global-spec
  {:db {:alias :d :default "emails.db" :desc "Datalevin database path"}})

(def ingest-spec
  {:source {:alias :s :default "google-takeout" :desc "Source label"}
   :batch  {:alias :b :coerce :long :default 100 :desc "Transaction batch size"
            :validate {:pred #(pos? %)
                       :ex-msg (fn [_] "Batch size must be positive")}}
   :debug  {:desc "Enable MIME tree debug logging to stderr"}})

(def query-spec
  {:subject     {:alias :s :desc "Filter by subject substring"}
   :from        {:alias :f :desc "Filter by sender"}
   :to          {:alias :t :desc "Filter by recipient"}
   :address     {:alias :a :desc "Filter by email address (from, to, or cc)"}
   :labels      {:alias :l :desc "Comma-separated Gmail labels"}
   :labels-mode {:desc "How to combine labels: any | all" :default "any"
                 :validate {:pred #{"any" "all"}
                            :ex-msg (fn [_] "Must be 'any' or 'all'")}}
   :text        {:desc "Search text in subject and body"}
   :since       {:desc "Emails on or after date (e.g. 2024-01-01)"}
   :before      {:desc "Emails before date"}
   :limit       {:alias :n :coerce :long :default 20 :desc "Max results"}
   :offset      {:coerce :long :default 0 :desc "Pagination offset"}
   :format      {:desc "table | edn | json" :default "edn"}
   :page        {:coerce :long :desc "Page number (1-indexed, overrides offset)"}
   :page-size   {:coerce :long :desc "Results per page (overrides --limit)"}})

(def export-spec
  {:format {:desc "json | edn" :default "json"}
   :label  {:alias :l :coerce [] :desc "Filter by label (can repeat)"}
   :since  {:desc "Filter by start date"}})

(def threads-spec
  {:thread-id    {:alias :t :desc "Show specific thread"}
   :participant  {:alias :p :desc "Find threads by participant"}
   :search       {:alias :s :desc "Search threads by subject"}
   :limit        {:alias :n :coerce :long :default 20 :desc "Max results"}
   :format       {:desc "table | edn | json" :default "table"}})

(def split-spec
  {:size   {:alias :s :coerce :long :default 500 :desc "Approximate chunk size in MB"}
   :output {:alias :o :desc "Output directory (default: same dir as input)"}})

;; ─── Command handlers ──────────────────────────────────────────

(defn- ingest-cmd [{:keys [opts args]}]
  (let [conn   (db/create-conn (:db opts))
        source (:source opts)
        bsize  (:batch opts)
        paths  (mapcat (fn [p]
                         (let [f (java.io.File. p)]
                           (if (.isDirectory f)
                             (filter #(str/ends-with? (.getName %) ".mbox")
                                     (.listFiles f))
                             [f])))
                       args)]
    (try
      (when (empty? paths)
        (println "No .mbox files found.")
        (System/exit 1))
      ;; Check for large files (> 1 GB) that may exceed MboxIterator limits
      (let [max-size (* 1024 1024 1024)
            large-files (filter #(> (.length %) max-size) paths)]
        (when (seq large-files)
          (println "Warning: Some files are larger than 1 GB and may not be ingestible directly:")
          (doseq [f large-files]
            (println (format "  %s (%.1f GB)" (.getName f) (/ (.length f) 1024.0 1024.0 1024.0))))
          (println "Consider splitting them first:")
          (println "  takeout split <file> -s 1024")
          (println)))
      (println (format "Ingesting %d file(s) (source: %s, batch: %d)..."
                       (count paths) source bsize))
      (when (:debug opts)
        (println "  Debug mode: MIME tree logging enabled"))
      (let [log-file (str (:db opts) "-ingest.log")]
        (doseq [f paths]
          (let [from-count (mbox/count-mbox-messages (.getAbsolutePath f))
                r (binding [parse/*debug* (:debug opts)]
                    (ingest/ingest-mbox! conn (.getAbsolutePath f) source
                                       :batch-size bsize :log-file log-file))]
            (println (format "  %-40s  %6d emails  %3d txs  (MboxIterator: %d, From_ lines: %d, errors: %d)"
                             (.getName f) (:email-count r) (:tx-count r) (:total-raw r) from-count (:parse-errors r))))))
      (println "Done.")
      (finally
        (db/close-conn conn)))))

(defn- query-cmd [{:keys [opts]}]
  (let [conn    (db/create-conn (:db opts))
        db-snap (d/db conn)]
    (try
      (let [clauses (build-query-clauses opts)
            query   (build-query clauses)
            results (d/q query db-snap)
            total   (count results)
            {:keys [_ page-results offset limit]} (paginated-results results opts)
            fmt     (keyword (:format opts))]
        (case fmt
          :table (print-table page-results)
          :edn   (clojure.pprint/pprint {:total total
                                         :offset offset
                                         :limit limit
                                         :results page-results})
          :json  (println (json/generate-string page-results))))
      (finally
        (db/close-conn conn)))))

(defn- stats-cmd [{:keys [opts]}]
  (let [conn (db/create-conn (:db opts))
        db   (d/db conn)]
    (try
      (println "Database statistics:\n")
      (let [total (d/q '[:find (count ?e) . :where [?e :email/subject]] db)]
        (println (format "  Total emails:     %d" total)))
      (let [range (d/q '[:find (min ?d) (max ?d) :where [?e :email/date ?d]] db)]
        (when-let [[lo hi] (first range)]
          (println (format "  Date range:       %s \u2192 %s" (str lo) (str hi)))))
      (println "  Top labels:")
      (let [labels (d/q '[:find ?label (count ?e) :where [?e :email/labels ?label]] db)]
        (doseq [[label cnt] (take 10 (sort-by second > labels))]
          (println (format "    %-25s %d" (str label) cnt))))
      (println "  Top senders:")
      (let [senders (d/q '[:find ?from (count ?e) :where [?e :email/from ?from]] db)]
        (doseq [[sender cnt] (take 10 (sort-by second > senders))]
          (println (format "    %-30s %d" (str sender) cnt))))
      (println "\n  Thread statistics:")
      (let [thread-count (d/q '[:find (count ?thread) . :where [?e :email/thread-id ?thread]] db)]
        (println (format "    Total threads:   %d" thread-count)))
      (let [threads-with-count (d/q '[:find ?thread (count ?e) :where [?e :email/thread-id ?thread]] db)
            avg-emails (if (pos? (count threads-with-count))
                         (/ (reduce + (map second threads-with-count))
                            (count threads-with-count))
                         0)]
        (println (format "    Avg emails/thread: %.1f" (double avg-emails))))
      (println "    Longest threads:")
      (let [threads (d/q '[:find ?thread (count ?e) :where [?e :email/thread-id ?thread]] db)]
        (doseq [[thread cnt] (take 5 (sort-by second > threads))]
          (let [summary (db/get-thread-summary db thread)]
            (println (format "      %-40s %d emails"
                             (subs (:subject summary) 0 (min 40 (count (:subject summary))))
                             cnt)))))
      (println "\n  Body length statistics:")
      (let [lengths (d/q '[:find [?len ...] :where [?e :email/body-length ?len]] db)]
        (when (seq lengths)
          (let [n (count lengths)
                sorted (sort lengths)
                total (reduce + 0 sorted)
                mean (double (/ total n))
                median (double (if (odd? n)
                         (nth sorted (quot n 2))
                         (/ (+ (nth sorted (dec (quot n 2))) (nth sorted (quot n 2))) 2.0)))
                min-len (first sorted)
                max-len (last sorted)]
            (println (format "    Mean:   %.0f chars" mean))
            (println (format "    Median: %.0f chars" median))
            (println (format "    Min:    %d chars" min-len))
            (println (format "    Max:    %d chars" max-len)))))
      (finally
        (db/close-conn conn)))))

(defn- export-cmd [{:keys [opts args]}]
  (let [conn (db/create-conn (:db opts))
        db   (d/db conn)]
    (try
      (when (empty? args)
        (println "Usage: takeout export [options] <output-file>")
        (System/exit 1))
      (let [label-clauses (when (seq (:label opts))
                            [(into [:or]
                                   (for [l (:label opts)]
                                     ['?e :email/labels l]))])
            clauses (cond-> (or label-clauses [['?e :email/subject]])
                      (:since opts)
                      (conj ['?e :email/date '?d]
                            [(list '>= '?d (parse-date (:since opts)))]))
            query   (vec (concat
                          [:find [(list 'pull '?e '[*]) (symbol "...")]
                           :in '$]
                          [:where]
                          clauses))
            results (d/q query db)
            output  (first args)]
        (println (format "Exporting %d emails to %s..." (count results) output))
        (case (keyword (:format opts))
          :json (spit output (json/generate-string results))
          :edn  (spit output (with-out-str (clojure.pprint/pprint results)))
          (println "Done.")))
      (finally
        (db/close-conn conn)))))

(defn- threads-cmd [{:keys [opts]}]
  (let [conn (db/create-conn (:db opts))
        db   (d/db conn)]
    (try
      (cond
        (:thread-id opts)
        (let [thread-id (:thread-id opts)
              emails (db/get-thread-emails db thread-id)]
          (println "Thread:" thread-id)
          (println "Emails:" (count emails))
          (doseq [e emails]
            (println "  From:" (:email/from e))
            (println "  Date:" (:email/date e))
            (println "  Subject:" (:email/subject e))
            (println)))

        (:participant opts)
        (let [threads (db/get-threads-by-participant db (:participant opts))]
          (println "Threads involving" (:participant opts) ":" (count threads))
          (doseq [tid threads]
            (let [summary (db/get-thread-summary db tid)]
              (println "  -" (:subject summary) "(" (:email-count summary) "emails)"))))

        (:search opts)
        (let [threads (db/search-threads-by-subject db (:search opts))]
          (println "Found" (count threads) "threads matching" (pr-str (:search opts)))
          (doseq [tid threads]
            (let [summary (db/get-thread-summary db tid)]
              (println "  -" (:subject summary) "(" (:email-count summary) "emails)"))))

        :else
        (let [threads (take (:limit opts) (db/get-recent-threads db 100))]
          (println "Recent threads:")
          (doseq [t threads]
            (println (format "  %-50s %3d emails  %s"
                             (:subject t) (:email-count t) (str (:last-date t)))))))
      (finally
        (db/close-conn conn)))))

;; ─── Labels command ────────────────────────────────────────────

(def labels-spec
  {:format {:desc "table | edn | json" :default "table"}
   :search {:alias :s :desc "Filter labels by substring"}})

(defn- labels-cmd [{:keys [opts]}]
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        fmt    (keyword (:format opts))
        search (:search opts)]
    (try
      (let [all-labels (db/get-all-labels db)
            filtered   (if search
                         (filter #(str/includes? (str/lower-case %) (str/lower-case search))
                                 all-labels)
                         all-labels)
            sorted     (sort (map str filtered))]
        (case fmt
          :table
          (do (println (str "\n" (count sorted) " labels"
                            (when search (str " matching \"" search "\""))
                            ":"))
              (println "  -----")
              (doseq [l sorted]
                (println (str "  " l))))
          :edn (clojure.pprint/pprint {:count (count sorted) :labels sorted})
          :json (println (str "[" (str/join ", " (map #(pr-str (str %)) sorted)) "]"))))
      (finally
        (db/close-conn conn)))))

;; ─── Addresses command ────────────────────────────────────────

(def addresses-spec
  {:format      {:desc "table | edn | json" :default "table"}
   :search      {:alias :s :desc "Filter addresses by substring"}
   :labels      {:alias :l :desc "Comma-separated labels to filter by"}
   :labels-mode {:desc "How to combine labels: any | all" :default "any"
                 :validate {:pred #{"any" "all"}
                            :ex-msg (fn [_] "Must be 'any' or 'all'")}}})

(defn- addresses-cmd [{:keys [opts]}]
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        fmt    (keyword (:format opts))
        search (:search opts)
        labels (:labels opts)]
    (try
      (let [labels-list (when labels (str/split labels #","))
            all-addrs   (if labels-list
                          (db/get-addresses-by-labels db labels-list
                                                      :labels-mode (keyword (:labels-mode opts "any")))
                          (db/get-all-addresses db))
            filtered    (if search
                          (filter #(str/includes? (str/lower-case %) (str/lower-case search))
                                  all-addrs)
                          all-addrs)
            sorted      (sort filtered)]
        (case fmt
          :table
          (do (println (str "\n" (count sorted) " addresses"
                            (when search (str " matching \"" search "\""))
                            ":"))
              (println "  -----")
              (doseq [a sorted]
                (println (str "  " a))))
          :edn (clojure.pprint/pprint {:count (count sorted) :addresses sorted})
          :json (println (str "[" (str/join ", " (map #(pr-str (str %)) sorted)) "]"))))
      (finally
        (db/close-conn conn)))))

;; ─── Propagate command ─────────────────────────────────────────

(def propagate-spec
  {:label   {:alias :l :desc "Label to propagate to threads" :required true}
   :dry-run {:desc "Preview only, don't transact"}
   :format  {:desc "table | edn | json" :default "table"}})

(defn- propagate-cmd [{:keys [opts]}]
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        label  (:label opts)
        fmt    (keyword (:format opts))]
    (try
      (if (:dry-run opts)
        (let [result (label/preview-propagation db label)]
          (println (str "\nDry-run for label \"" label "\":"))
          (println (str "  " (:threads result) " threads affected"))
          (println (str "  " (:total-emails result) " total emails in those threads"))
          (println (str "  " (:updated result) " emails would receive the label"))
          (when (seq (:previews result))
            (println "\n  Sample of emails to update:")
            (doseq [p (:previews result)]
              (println (str "    " (:subject p) " — labels: " (:existing-labels p))))))
        (let [result (label/propagate-label-to-threads conn label)]
          (println (str "\nPropagated label \"" label "\" to threads:"))
          (println (str "  " (:threads result) " threads affected"))
          (println (str "  " (:total-emails result) " total emails in those threads"))
          (println (str "  " (:updated result) " emails updated"))))
      (finally
        (db/close-conn conn)))))

;; ─── Inspect thread command ────────────────────────────────────

(def inspect-thread-spec
  {:thread-id {:alias :t :desc "Thread ID to inspect" :required true}
   :label     {:alias :l :desc "Check coverage for a specific label"}
   :format    {:desc "table | edn | json" :default "table"}})

(defn- inspect-thread-cmd [{:keys [opts]}]
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        tid    (str (:thread-id opts))
        fmt    (keyword (:format opts))]
    (try
      (let [result (if (:label opts)
                     (label/inspect-thread-labels db tid :label (:label opts))
                     (label/inspect-thread-labels db tid))]
        (case fmt
          :table
          (do (println (str "\nThread: " tid))
              (println (str "Emails: " (:email-count result)))
              (println (str "All labels: " (clojure.string/join ", " (:all-labels result))))
              (println (str "Common labels: " (clojure.string/join ", " (:common-labels result))))
              (when (seq (:sparse-labels result))
                (println (str "Sparse labels: " (clojure.string/join ", " (:sparse-labels result)))))
              (when (:label-coverage result)
                (let [cov (:label-coverage result)]
                  (println (str "\nLabel \"" (:label cov) "\" coverage: "
                               (:present cov) "/" (:total cov) " emails"))))
              (println)
              (doseq [e (:emails result)]
                (println (str "  " (:date e) " | " (:from e)))
                (println (str "  " (:subject e)))
                (println (str "  labels: " (clojure.string/join ", " (:labels e))))
                (println)))
          :edn (clojure.pprint/pprint result)
          :json (println (json/generate-string result))))
      (finally
        (db/close-conn conn)))))

;; ─── Linked labels command ─────────────────────────────────────

(def linked-labels-spec
  {:label  {:alias :l :desc "Label to find linked labels for" :required true}
   :format {:desc "table | edn | json" :default "table"}})

(defn- linked-labels-cmd [{:keys [opts]}]
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        label  (:label opts)
        fmt    (keyword (:format opts))]
    (try
      (let [linked (label/fetch-linked-labels db label)]
        (case fmt
          :table
          (do (println (str "\nLabels linked to \"" label "\":"))
              (println "  -----")
              (doseq [l linked]
                (println (str "  " l))))
          :edn (clojure.pprint/pprint {:label label :linked linked})
          :json (println (str "[" (str/join ", " (map #(pr-str (str %)) linked)) "]"))))
      (finally
        (db/close-conn conn)))))

;; ─── Frequencies command ───────────────────────────────────────

(def frequencies-spec
  {:search {:alias :s :desc "Filter labels by substring (case-insensitive)"}
   :format {:desc "table | edn | json" :default "table"}
   :limit  {:alias :n :coerce :long :default 0 :desc "Max results (0 = all)"}})

(defn- frequencies-cmd [{:keys [opts]}]
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        fmt    (keyword (:format opts))
        search (:search opts)
        limit  (:limit opts)]
    (try
      (let [freqs (db/get-label-frequencies db)
            filtered (if search
                       (filter #(str/includes? (str/lower-case (first %)) (str/lower-case search))
                               freqs)
                       freqs)
            limited  (if (pos? limit) (take limit filtered) filtered)
            total    (count filtered)]
        (case fmt
          :table
          (do (println (str "\n" total " labels"
                            (when search (str " matching \"" search "\""))
                            ":"))
              (println "  " (format "%-35s %s" "Label" "Count"))
              (println "  " (apply str (repeat 45 "-")))
              (doseq [[l c] limited]
                (println "  " (format "%-35s %d" l c))))
          :edn (clojure.pprint/pprint {:total total :frequencies (vec limited)})
          :json (println (json/generate-string (for [[l c] limited]
                                                    {:label l :count c})))))
      (finally
        (db/close-conn conn)))))

;; ─── Query building ────────────────────────────────────────────

(defn- build-query-clauses [opts]
  (let [labels-str    (:labels opts)
        labels        (when labels-str (str/split labels-str #","))
        labels-mode   (keyword (:labels-mode opts "all"))
        text          (:text opts)]
    (cond-> []
      labels
      (into (case labels-mode
              :any  (let [patterns (for [l labels]
                                     ['?e :email/labels l])]
                      (if (= 1 (count patterns))
                        patterns
                        [(cons 'or patterns)]))
              :all  (for [l labels]
                      ['?e :email/labels l])))
      (:subject opts) (conj ['?e :email/subject '?s]
                            [(list 'clojure.string/includes? '?s (:subject opts))])
      (:from opts)    (conj ['?e :email/from (:from opts)])
      (:to opts)      (conj ['?e :email/to (:to opts)])
      (:address opts) (conj (list 'or ['?e :email/from (:address opts)]
                                  ['?e :email/to (:address opts)]
                                  ['?e :email/cc (:address opts)]))
      text            (conj (list 'or ['?e :email/subject '?txt]
                                  ['?e :email/body-truncated '?txt])
                            [(list 'clojure.string/includes?
                                   (list 'clojure.string/lower-case '?txt)
                                   (clojure.string/lower-case text))])
      (:since opts)   (conj ['?e :email/date '?d]
                            [(list '>= '?d (parse-date (:since opts)))])
      (:before opts)  (conj ['?e :email/date '?d]
                            [(list '< '?d (parse-date (:before opts)))])
      (empty? (select-keys opts [:subject :from :to :address :text :since :before]))
      (conj ['?e :email/subject]))))

(defn- build-query [clauses]
  (let [pull-pattern [:email/id :email/subject :email/from :email/to
                      :email/date :email/labels :email/links
                      :email/body-truncated
                      :email/gmail-id :email/thread-id]]
    (vec (concat
          [:find [(list 'pull '?e (vec pull-pattern)) (symbol "...")]]
          [:where]
          clauses))))

(defn- apply-limit-offset [results limit offset]
  (let [offset (or offset 0)
        limit  (or limit 0)]
    (cond
      (and (pos? limit) (pos? offset))
      (take limit (drop offset results))
      (pos? limit)
      (take limit results)
      (pos? offset)
      (drop offset results)
      :else results)))

(defn- paginated-results [results opts]
  (let [page-size (or (:page-size opts) (:limit opts) 20)
        page      (:page opts)
        offset    (if page
                    (* (dec page) page-size)
                    (or (:offset opts) 0))]
    {:results     results
     :page-results (apply-limit-offset results page-size offset)
     :offset      offset
     :limit       page-size}))

;; ─── Output formatting ─────────────────────────────────────────

(defn- get-col-width [] 40)

(defn- print-table [results]
  (when (empty? results)
    (println "(no results)")
    (System/exit 0))
  (let [cols   [:email/subject :email/from :email/date :email/labels]
        header (str/join " │ " (for [c cols]
                                 (format (str "%-" (get-col-width) "s") (name c))))
        sep    (str/join "─┼─" (repeat (count cols)
                                       (apply str (repeat (get-col-width) "─"))))]
    (println header)
    (println sep)
    (doseq [row results]
      (println (str/join " │ "
                         (for [c cols]
                           (let [v (get row c)]
                             (format (str "%." (get-col-width) "s")
                                     (cond
                                       (nil? v)   ""
                                       (coll? v)  (str/join ", " (take 3 v))
                                       (inst? v)  (str v)
                                       :else      (subs (str v) 0 (min (get-col-width) (count (str v)))))))))))
    (println)))

;; ─── Split command (zero-copy NIO splitter) ─────────────────────

;; ─── MBOX info diagnostic ──────────────────────────────────────

(defn split-cmd [{:keys [opts args]}]
  (let [chunk-size-mb (or (:size opts) 500)
        out-dir (:output opts)
        clean-args (loop [as args, acc []]
                     (if (empty? as)
                       acc
                       (if (or (= "-o" (first as)) (= "--output" (first as)))
                         (recur (drop 2 as) acc)
                         (recur (rest as) (conj acc (first as))))))]
    (when (empty? clean-args)
      (println "Error: No .mbox file specified.")
      (println "Usage: takeout split [options] <mbox-file>...")
      (println "Options:")
      (println "  -s, --size MB     Chunk size in MB (default 500)")
      (println "  -o, --output DIR  Output directory")
      (System/exit 1))
    (doseq [path clean-args]
      (let [f (java.io.File. path)]
        (cond
          (not (.exists f))
          (println "File not found:" path)
          (.isDirectory f)
          (println "Skipping directory (use a specific .mbox file):" path)
          (not (str/ends-with? (.getName f) ".mbox"))
          (println "Warning: Not an .mbox file, skipping:" path)
          :else
          (let [target-dir (or out-dir (.getParent f) ".")]
            (println (format "Splitting %s (%d MB/chunk) into %s/"
                             (.getName f) chunk-size-mb target-dir))
            (mbox/split-mbox! (.getAbsolutePath f) chunk-size-mb target-dir)))))))

(def mbox-info-spec
  {:format {:desc "table | edn | json" :default "table"}})

(defn- mbox-info-cmd [{:keys [opts args]}]
  (let [fmt (keyword (:format opts))]
    (when (empty? args)
      (println "Usage: takeout mbox-info <mbox-file>...")
      (System/exit 1))
    (doseq [path args]
      (let [f (java.io.File. path)]
        (if (not (.exists f))
          (println "File not found:" path)
          (let [file-len (.length f)
                msg-count (mbox/count-mbox-messages path)
                info {:file (.getName f)
                      :path (.getAbsolutePath f)
                      :size-mb (float (/ file-len (* 1024 1024)))
                      :messages msg-count}]
            (case fmt
              :table (println (format "%s: %d messages (%.0f MB)" (:file info) (:messages info) (:size-mb info)))
              :edn (clojure.pprint/pprint info)
              :json (println (json/generate-string info)))))))))

;; ─── Dispatch ───────────────────────────────────────────────────

(def update-msg-ids-spec
  {:from {:alias :f :desc "EDN file from map_gmail_ids.bb (required)" :required true}
   :dry-run {:desc "Preview updates without transacting"}})

(defn- update-msg-ids-cmd [{:keys [opts]}]
  (when (:help opts)
    (println "Usage: takeout update-message-ids --from <edn-file> [--dry-run]")
    (println)
    (println "  -f, --from PATH  EDN file from map_gmail_ids.clj (required)")
    (println "  --dry-run        Preview updates without transacting")
    (println "  --force          Update even if already set")
    (System/exit 0))
  (let [conn    (db/create-conn (:db opts))
        db      (d/db conn)
        mapping (edn/read-string (slurp (:from opts)))
        force?   (:force opts)]
    (try
      (let [entries mapping
            total   (count entries)
            updated (atom 0)
            missing (atom 0)
            skipped-gws (atom 0)
            skipped-already (atom 0)]
        (println (format "Processing %d entries from %s..." total (:from opts)))
        (doseq [[msg-id {:keys [gmail-id thread-id error]}] entries]
          (cond
            (not gmail-id)
            (do
              (swap! skipped-gws inc)
              (println (format "  SKIP: %s (error: %s)"
                               (subs (or (str msg-id) "?") 0 (min 50 (count (str msg-id))))
                               (or error "no gmail-id"))))

            :else
            (let [entity (d/entity db [:email/id msg-id])]
              (if entity
                (if (and (:email/gmail-id entity) (not force?))
                  (do
                    (swap! skipped-already inc)
                    (println (format "  SKIP (already set): %s"
                                    (subs msg-id 0 (min 50 (count msg-id))))))
                  (let [eid (:db/id entity)
                        txn (if (:dry-run opts)
                              []
                              [{:db/id           eid
                                :email/gmail-id  gmail-id
                                :email/thread-id thread-id}])]
                    (when (seq txn)
                      (d/transact! conn txn))
                    (swap! updated inc)
                    (when (:dry-run opts)
                      (println (format "  %-50s gmail-id=%s thread=%s"
                                      (subs msg-id 0 (min 50 (count msg-id)))
                                      gmail-id thread-id)))))
                (do
                  (swap! missing inc)
                  (println (format "  NOT FOUND: %s"
                                  (subs msg-id 0 (min 60 (count msg-id))))))))))
        (println (format "\nDone: %d updated, %d skipped (errors), %d already set, %d not found, %d total"
                         @updated @skipped-gws @skipped-already @missing total))
        (db/close-conn conn)))))

(def update-bodies-spec
  {:from {:alias :f :desc "EDN file from fetch-bodies.clj (required)" :required true}
   :dry-run {:desc "Preview updates without transacting"}})

(defn- update-bodies-cmd [{:keys [opts]}]
  (when (:help opts)
    (println "Usage: takeout update-bodies --from <edn-file> [--dry-run]")
    (println)
    (println "  -f, --from PATH  EDN file from fetch-bodies.clj (required)")
    (println "  --dry-run        Preview updates without transacting")
    (System/exit 0))
  (let [conn    (db/create-conn (:db opts))
        db      (d/db conn)
        mapping (edn/read-string (slurp (:from opts)))]
    (println (format "Processing %d entries from %s..." (count mapping) (:from opts)))
    (let [updated (atom 0)
          skipped (atom 0)
          missing (atom 0)]
      (doseq [[msg-id {:keys [body gmail-id error]}] mapping]
        (cond
          (not body)
          (do (swap! skipped inc)
              (println (format "  SKIP (no body): gmail-id=%s msg-id=%s error=%s"
                               gmail-id (subs (or msg-id "?") 0 40) (or error "?"))))
          :else
          (let [entity (d/entity db [:email/id msg-id])]
            (if entity
              (let [text (parse/html->text body)
                    text (if (str/blank? text) (str/trim (str/replace body #"<[^>]+>" "")) text)
                    text (subs text 0 (min 10000 (count text)))
                    txn  (if (:dry-run opts)
                           []
                           [{:db/id              (:db/id entity)
                             :email/body-truncated text
                             :email/body-length   (count text)}])]
                (when (seq txn)
                  (d/transact! conn txn))
                (swap! updated inc)
                (when (:dry-run opts)
                  (println (format "  %-50s body=%d chars"
                                  (subs msg-id 0 (min 50 (count msg-id)))
                                  (count text)))))
              (do (swap! missing inc)
                  (println (format "  NOT FOUND: %s" (subs msg-id 0 (min 60 (count msg-id))))))))))
      (println (format "\nDone: %d updated, %d skipped (no body), %d not found, %d total"
                       @updated @skipped @missing (count mapping)))
      (db/close-conn conn))))

(def extract-urls-spec
  {:label  {:alias :l :desc "Gmail label to query (required)" :required true}
   :output {:alias :o :desc "Output file path (stdout if not specified)"}
   :limit  {:alias :n :desc "Max emails (default: 100)"}
   :offset {:desc "Offset for pagination (default: 0)"}
   :format {:desc "Output format: edn | json" :default "edn"}})

(defn- extract-urls-cmd [{:keys [opts]}]
  (when (:help opts)
    (println "Usage: takeout extract-urls -l <label> [-o <file>] [-n <limit>] [--offset <n>]")
    (println)
    (println "  -l, --label LABEL  Gmail label (required)")
    (println "  -o, --output PATH  Output file (stdout if omitted)")
    (println "  -n, --limit N     Max emails (default: 100)")
    (println "  --offset N        Offset for pagination (default: 0)")
    (println "  --format FMT      Output: edn | json (default: edn)")
    (System/exit 0))
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        label  (:label opts)
        output (:output opts)
        limit  (or (:limit opts) 100)
        offset (or (:offset opts) 0)
        fmt    (keyword (:format opts))]
    (try
      (let [results
            (->> (d/q '[:find [(pull ?e [:email/id :email/subject :email/body-truncated]) ...]
                        :in $ ?label
                        :where [?e :email/labels ?label]]
                      db label)
                 (drop offset)
                 (take limit)
                 (keep (fn [item]
                         (let [body (:email/body-truncated item)
                               urls (cp/extract-urls body)]
                           (cond
                             (seq urls)
                             [(:email/id item) urls]
                             (str/blank? body)
                             (do (println (str "  (no body) " (:email/id item)))
                                 nil)
                             :else
                             (do (println (str "  (no URL) " (:email/id item)))
                                 nil)))))
                 (into {}))
            output-str (case fmt
                         :edn  (with-out-str (clojure.pprint/pprint results))
                         :json (json/generate-string results))]
        (if output
          (spit output output-str)
          (print output-str))
        (println (str "\n" (count results) " emails with URLs (label: " label ")"
                      (when output (str " -> " output)))))
      (finally
        (db/close-conn conn)))))

(def update-links-spec
  {:from {:alias :f :desc "EDN file from extract-urls (required)" :required true}
   :dry-run {:desc "Preview updates without transacting"}})

(defn- update-links-cmd [{:keys [opts]}]
  (when (:help opts)
    (println "Usage: takeout update-links --from <edn-file> [--dry-run]")
    (println)
    (println "  -f, --from PATH  EDN file from extract-urls (required)")
    (println "  --dry-run        Preview updates without transacting")
    (System/exit 0))
  (let [conn    (db/create-conn (:db opts))
        db      (d/db conn)
        mapping (edn/read-string (slurp (:from opts)))]
    (println (format "Processing %d entries from %s..." (count mapping) (:from opts)))
    (let [updated (atom 0)
          missing (atom 0)
          empty   (atom 0)]
      (doseq [[msg-id urls] mapping]
        (if (seq urls)
          (let [entity (d/entity db [:email/id msg-id])]
            (if entity
              (let [txn (if (:dry-run opts)
                          []
                          [{:db/id      (:db/id entity)
                            :email/links urls}])]
                (when (seq txn)
                  (d/transact! conn txn))
                (swap! updated inc)
                (when (:dry-run opts)
                  (println (format "  %-50s %d links"
                                  (subs msg-id 0 (min 50 (count msg-id)))
                                  (count urls)))))
              (do (swap! missing inc)
                  (println (format "  NOT FOUND: %s" (subs msg-id 0 (min 60 (count msg-id))))))))
          (do (swap! empty inc)
              (println (format "  SKIP (no links): %s" (subs msg-id 0 (min 50 (count msg-id))))))))
      (println (format "\nDone: %d updated, %d skipped (no links), %d not found, %d total"
                       @updated @empty @missing (count mapping)))
      (db/close-conn conn))))

(def upsert-content-spec
  {:from {:alias :f :desc "EDN file from fetch-content.clj (required)" :required true}
   :dry-run {:desc "Preview without transacting"}})

(defn- upsert-content-cmd [{:keys [opts]}]
  (when (:help opts)
    (println "Usage: takeout upsert-content --from <edn-file> [--dry-run]")
    (println)
    (println "  -f, --from PATH  EDN file from fetch-content.clj (required)")
    (println "  --dry-run        Preview without transacting")
    (System/exit 0))
  (let [conn     (db/create-conn (:db opts))
        db       (d/db conn)
        mapping  (edn/read-string (slurp (:from opts)))
        type-map {:arxiv :paper :github :git-repo :youtube :video-transcript}
        md       (java.security.MessageDigest/getInstance "SHA-256")]
    (println (format "Processing %d emails from %s..." (count mapping) (:from opts)))
    (let [upserted (atom 0)
          skipped  (atom 0)]
      (doseq [[email-id {:keys [links]}] mapping]
        (doseq [[url content] links]
          (let [content-type (type-map (:type content))
                url-hash     (format "%064x" (BigInteger. 1 (.digest md (.getBytes url "UTF-8"))))
                url-host     (or (second (re-find #"https?://([^/]+)" url)) "unknown")
                body-text    (or (:readme content) (:xml content)
                                 (:transcript content)
                                 (when (:title content)
                                   (str (:title content) "\n\n" (:description content))))]
            (if (and content-type (not (str/blank? body-text)))
              (let [txn (if (:dry-run opts)
                          []
                          [{:content/id           url-hash
                            :content/url          url
                            :content/host         url-host
                            :content/type         content-type
                            :content/body         body-text
                            :content/source-email email-id
                            :content/labels       (:email/labels (d/entity db [:email/id email-id]))}])]
                (when (seq txn)
                  (d/transact! conn txn))
                (swap! upserted inc)
                (when (:dry-run opts)
                  (println (format "  %s %s (%d chars)"
                                  (name content-type)
                                  url-host
                                  (count body-text)))))
              (do (swap! skipped inc)
                  (println (format "  SKIP (no body): %s" (subs url 0 (min 60 (count url))))))))))
      (println (format "\nDone: %d content entities, %d skipped, from %d emails"
                       @upserted @skipped (count mapping)))
      (db/close-conn conn))))

(def content-query-spec
  {:label {:alias :l :desc "Filter by email label"}
   :host  {:alias :h :desc "Filter by content host (arxiv.org, github.com, etc.)"}
   :type  {:alias :t :desc "Content type: paper, git-repo, video-transcript"}
   :limit {:alias :n :desc "Max results (default: 50)"}
   :format {:desc "Output: edn | json | table" :default "table"}})

(defn- content-query-cmd [{:keys [opts]}]
  (let [conn   (db/create-conn (:db opts))
        db     (d/db conn)
        label  (:label opts)
        host   (:host opts)
        ctype  (:type opts)
        limit  (or (:limit opts) 50)
        fmt    (keyword (:format opts))]
    (try
      (let [;; Build query with optional :content/labels filter
              base-clauses '[[?c :content/source-email ?email-id]
                             [?e :email/id ?email-id]
                             [?e :email/subject ?subject]
                             [?e :email/from ?from]
                             [?e :email/date ?date]]
              label-clause (when label '[:content/labels label])
              query (vec (concat '[:find (pull ?c [*]) ?subject ?from ?date :where]
                                 base-clauses
                                 (when label [label-clause])))
              all (d/q query db)
            by-host (if host (filterv (fn [[c]] (= host (:content/host c))) all) all)
            by-type (if ctype (filterv (fn [[c]] (= (keyword ctype) (:content/type c))) by-host) by-host)
            results (take limit by-type)]
        (case fmt
          :table
          (doseq [[c subject from date] results]
            (println (format "%-15s %-8s %-55s %s" (name (:content/type c)) (:content/host c)
                             (subs (:content/url c) 0 (min 55 (count (:content/url c)))) subject))
            (println (format "  from: %-30s date: %s" from (str date)))
            (println))
          :edn
          (clojure.pprint/pprint (mapv (fn [[c s f d]] {:content c :subject s :from f :date d}) results))
          :json
          (println (json/generate-string (mapv (fn [[c s f d]] {:content c :subject s :from f :date d}) results))))
        (println (str "\n" (count results) " results")))
      (finally
        (db/close-conn conn)))))

(def cli-tree
  "Command dispatch table for babashka/cli."
  [{:cmds [] :spec global-spec :fn (fn [{:keys [opts] :as m}]
                                      (if (:help opts)
                                        (do
                                          (println "Usage: takeout [options] <command> [args]")
                                          (println)
                                          (println "Options:")
                                          (println "  -d, --db PATH  Database path (default: emails.db)")
                                          (println)
                                          (println "Commands:")
                                          (println "  ingest <files...>           Load MBOX files into the database")
                                          (println "  query                       Search emails by criteria")
                                          (println "  stats                       Show DB summary statistics")
                                          (println "  export                      Dump emails as JSON/EDN")
                                          (println "  threads                     List and explore email threads")
                                          (println "  split <files...>            Split large MBOX files into smaller chunks")
                                          (println "  labels                      List all email labels")
                                          (println "  addresses                   List all email addresses")
                                          (println "  frequencies                 Show label frequency distribution")
                                          (println "  mbox-info <files...>        Show MBOX file message count and size")
                                          (println "  propagate                   Propagate a label to all emails in threads")
                                          (println "  inspect-thread              Inspect label distribution within a thread")
                                          (println "  linked-labels               Find labels that co-occur with a given label")
                                          (println "  extract-urls                Extract URLs from email bodies by label")
                                          (println "  update-message-ids          Add :email/gmail-id from gws mapping EDN")
                                          (println "  update-bodies               Update :email/body-truncated from fetch-bodies EDN")
                                          (println "  update-links                Update :email/links from extract-urls EDN")
                                          (println "  upsert-content              Store fetched content as :content entities")
                                          (println "  content                     Query content entities joined with emails")
                                          (println "  extract-urls                Extract URLs from email bodies by label")
                                          (println)
                                          (println "Run 'takeout <command> --help' for command-specific options."))
                                        (println "No command specified. Use --help for usage.")))}
   {:cmds ["ingest"]  :fn ingest-cmd  :spec ingest-spec  :doc "Load MBOX files into the database"}
   {:cmds ["query"]   :fn query-cmd   :spec query-spec   :doc "Search emails by criteria"}
   {:cmds ["stats"]   :fn stats-cmd                      :doc "Show DB summary statistics"}
   {:cmds ["export"]  :fn export-cmd  :spec export-spec  :doc "Dump emails as JSON/EDN"}
   {:cmds ["threads"] :fn threads-cmd :spec threads-spec :doc "List and explore email threads"}
   {:cmds ["split"]   :fn split-cmd   :spec split-spec   :doc "Split large MBOX files into smaller chunks"}
   {:cmds ["labels"]  :fn labels-cmd  :spec labels-spec  :doc "List all email labels"}
   {:cmds ["addresses"] :fn addresses-cmd :spec addresses-spec :doc "List all email addresses"}
   {:cmds ["frequencies"] :fn frequencies-cmd :spec frequencies-spec :doc "Show label frequency distribution"}
   {:cmds ["mbox-info"] :fn mbox-info-cmd :spec mbox-info-spec :doc "Show MBOX file message count and size"}
   {:cmds ["propagate"] :fn propagate-cmd :spec propagate-spec :doc "Propagate a label to all emails in threads containing it"}
   {:cmds ["inspect-thread"] :fn inspect-thread-cmd :spec inspect-thread-spec :doc "Inspect label distribution within a thread"}
   {:cmds ["linked-labels"] :fn linked-labels-cmd :spec linked-labels-spec :doc "Find labels that co-occur with a given label"}
   {:cmds ["update-message-ids"] :fn update-msg-ids-cmd :spec update-msg-ids-spec :doc "Add :email/gmail-id and update :email/thread-id from gws mapping EDN"}
   {:cmds ["update-bodies"] :fn update-bodies-cmd :spec update-bodies-spec :doc "Update :email/body-truncated from fetch-bodies EDN"}
   {:cmds ["update-links"] :fn update-links-cmd :spec update-links-spec :doc "Update :email/links from extract-urls EDN"}
   {:cmds ["upsert-content"] :fn upsert-content-cmd :spec upsert-content-spec :doc "Store fetched content as :content entities"}
   {:cmds ["content"] :fn content-query-cmd :spec content-query-spec :doc "Query content entities joined with source emails"}
   {:cmds ["extract-urls"] :fn extract-urls-cmd :spec extract-urls-spec :doc "Extract URLs from email bodies by label"}])
