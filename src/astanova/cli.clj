(ns astanova.cli
  "Command-line interface: argument parsing, dispatch, formatting."
  (:require [astanova.db :as db]
            [astanova.ingest :as ingest]
            [astanova.parse :as parse]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalevin.core :as d])
  (:import [java.time Instant LocalDate ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter DateTimeParseException]))

;; ─── Forward declarations ───────────────────────────────────────-

(declare build-query-clauses build-query print-table to-json apply-limit-offset)

;; ─── Global options ─────────────────────────────────────────────

(defn- get-global-spec []
  [["-d" "--db PATH" "Datalevin database path"
    :default "emails.db"]
   ["-h" "--help"]])

;; ─── Ingest command ─────────────────────────────────────────────

(defn- get-ingest-spec []
  [["-s" "--source S" "Source label"
    :default "google-takeout"]
   ["-b" "--batch N" "Transaction batch size"
    :default 100
    :parse-fn #(Integer/parseInt %)
    :validate [#(pos? %) "Must be a positive integer"]]
   ["-h" "--help"]])

(defn- ingest-cmd [conn args]
  (let [{:keys [options arguments errors summary]} (parse-opts args (get-ingest-spec))]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (or (:help options) (empty? arguments))
      (println "Usage: takeout ingest [options] <path...>\n")
      (println "  Load one or more .mbox files or directories into the database.\n")
      (println summary)
      (System/exit (if (:help options) 0 1)))
    (let [paths  (mapcat (fn [p]
                           (let [f (java.io.File. p)]
                             (if (.isDirectory f)
                               (filter #(str/ends-with? (.getName %) ".mbox")
                                       (.listFiles f))
                               [f])))
                         arguments)
          source (:source options)
          bsize  (:batch options)]
      (when (empty? paths)
        (println "No .mbox files found.")
        (System/exit 1))
      (println (format "Ingesting %d file(s) (source: %s, batch: %d)..."
                       (count paths) source bsize))
      (doseq [f paths]
        (let [r (ingest/ingest-mbox! conn (.getAbsolutePath f) source
                                     :batch-size bsize)]
          (println (format "  %-40s  %6d emails  %3d txs"
                           (.getName f) (:email-count r) (:tx-count r)))))
      (println "Done."))))

;; ─── Date parsing ───────────────────────────────────────────────

(defn- parse-date
  "Parse an ISO date string like '2024-01-01' or '2024-01-01T10:00:00Z'
   into a java.util.Date for Datalevin comparison."
  [s]
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

;; ─── Query command ──────────────────────────────────────────────

(defn- get-query-spec []
  [["-s" "--subject TEXT" "Filter by subject substring" :id :subject]
   ["-f" "--from ADDR"    "Filter by sender"            :id :from]
   ["-t" "--to ADDR"      "Filter by recipient"          :id :to]
   ["-l" "--label LABEL"  "Filter by Gmail label"        :id :label]
   ["--since DATE"  "Emails on or after date (e.g. 2024-01-01)" :id :since]
   ["--before DATE" "Emails before date"                        :id :before]
   ["-n" "--limit N" "Max results"
    :default 20
    :parse-fn #(Integer/parseInt %)
    :validate [#(pos? %) "Must be positive"]]
   ["--offset N" "Pagination offset"
    :default 0
    :parse-fn #(Integer/parseInt %)
    :validate [#(not (neg? %)) "Must be non-negative"]
    :id :offset]
   ["--format FMT" "Output format: table | edn | json"
    :default "table"
    :validate [#(#{"table" "edn" "json"} %) "Must be table, edn, or json"]
    :id :format]
   ["-h" "--help"]])

(defn- query-cmd [conn args]
  (let [{:keys [options errors summary]} (parse-opts args (get-query-spec))]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (:help options)
      (println "Usage: takeout query [options]\n")
      (println "  Search emails by criteria.  If no filter given, shows all.\n")
      (println summary)
      (System/exit 0))
    (let [clauses (build-query-clauses options)
          query   (build-query clauses (:limit options) (:offset options))
          results (d/q query (d/db conn))
          limited (apply-limit-offset results (:limit options) (:offset options))
          fmt     (keyword (:format options))]
      (case fmt
        :table (print-table limited)
        :edn   (prn limited)
        :json  (println (to-json limited))))))

(defn- build-query-clauses [opts]
  (cond-> []
    (:subject opts) (conj ['?e :email/subject '?s]
                          [(list 'clojure.string/includes? '?s (:subject opts))])
    (:from opts)    (conj ['?e :email/from (:from opts)])
    (:to opts)      (conj ['?e :email/to (:to opts)])
    (:label opts)   (conj ['?e :email/labels (:label opts)])
    (:since opts)   (conj ['?e :email/date '?d]
                          [(list '>= '?d (parse-date (:since opts)))])
    (:before opts)  (conj ['?e :email/date '?d]
                          [(list '< '?d (parse-date (:before opts)))])
    (empty? (select-keys opts [:subject :from :to :label :since :before]))
    (conj ['?e :email/subject])))

(defn- build-query [clauses _limit _offset]
  ;; Build query - limit/offset applied to results, not in query
  ;; Construct the query vector properly with pull syntax
  (let [find-clause [:find '[(pull ?e [:email/subject :email/from :email/to 
                                   :email/date :email/labels]) ...]]]
    (vec (concat find-clause [:where] clauses))))

(defn- apply-limit-offset [results limit offset]
  (let [offset (or offset 0)
        limit (or limit 0)]
    (cond
      (and (pos? limit) (pos? offset))
      (take limit (drop offset results))
      
      (pos? limit)
      (take limit results)
      
      (pos? offset)
      (drop offset results)
      
      :else results)))

;; ─── Stats command ──────────────────────────────────────────────

(defn- stats-cmd [conn _args]
  (let [db (d/db conn)]
    (println "Database statistics:\n")

    (let [total (d/q '[:find (count ?e) .
                       :where [?e :email/subject]] db)]
      (println (format "  Total emails:     %d" total)))

    (let [range (d/q '[:find (min ?d) (max ?d)
                       :where [?e :email/date ?d]] db)]
      (when-let [[lo hi] (first range)]
        (println (format "  Date range:       %s \u2192 %s" (str lo) (str hi)))))

    (println "  Top labels:")
    (let [labels (d/q '[:find ?label (count ?e)
                        :where [?e :email/labels ?label]]
                      db)]
      (doseq [[label cnt] (take 10 (sort-by second > labels))]
        (println (format "    %-25s %d" (str label) cnt))))

    (println "  Top senders:")
    (let [senders (d/q '[:find ?from (count ?e)
                         :where [?e :email/from ?from]]
                       db)]
      (doseq [[sender cnt] (take 10 (sort-by second > senders))]
        (println (format "    %-30s %d" (str sender) cnt))))

    (println "\n  Thread statistics:")
    (let [thread-count (d/q '[:find (count ?thread) .
                            :where [?e :email/thread-id ?thread]] db)]
      (println (format "    Total threads:   %d" thread-count)))
    
    (let [threads-with-count (d/q '[:find ?thread (count ?e)
                                    :where [?e :email/thread-id ?thread]]
                                  db)
          avg-emails (if (pos? (count threads-with-count))
                     (/ (reduce + (map second threads-with-count))
                        (count threads-with-count))
                     0)]
      (println (format "    Avg emails/thread: %.1f" (double avg-emails))))
    
    (println "    Longest threads:")
    (let [threads (d/q '[:find ?thread (count ?e)
                       :where [?e :email/thread-id ?thread]]
                     db)]
      (doseq [[thread cnt] (take 5 (sort-by second > threads))]
        (let [summary (db/get-thread-summary db thread)]
          (println (format "      %-40s %d emails" 
                           (subs (:subject summary) 0 (min 40 (count (:subject summary))))
                           cnt)))))))

;; ─── Export command ─────────────────────────────────────────────

(defn- get-export-spec []
  [["--format FMT" "Output format: json | edn"
    :default "json"
    :validate [#(#{"json" "edn"} %) "Must be json or edn"]
    :id :format]
   ["-l" "--label LABEL" "Filter by label (can repeat)"
    :assoc-fn (fn [m _ label]
                (update m :labels conj label))]
   ["--since DATE" "Filter by start date" :id :since]
   ["-h" "--help"]])

(defn- export-cmd [conn args]
  (let [{:keys [options arguments errors summary]} (parse-opts args (get-export-spec))]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (or (:help options) (empty? arguments))
      (println "Usage: takeout export [options] <output-file>\n")
      (println "  Dump emails as JSON or EDN.\n")
      (println summary)
      (System/exit (if (:help options) 0 1)))
    (let [db      (d/db conn)
          ;; Build per-label OR clauses
          label-clauses (when (seq (:labels options))
                          [(into [:or]
                                 (for [l (:labels options)]
                                   ['?e :email/labels l]))])
          clauses (cond-> (or label-clauses [['?e :email/subject]])
                    (:since options)
                    (conj ['?e :email/date '?d]
                          [(list '>= '?d (parse-date (:since options)))]))
          query   (vec (concat
                        [:find [(list 'pull '?e '[*]) (symbol "...")]
                         :in '$]
                        [:where]
                        clauses))
          results (d/q query db)
          output  (first arguments)]
      (println (format "Exporting %d emails to %s..." (count results) output))
      (case (keyword (:format options))
        :json (spit output (to-json results))
        :edn  (spit output (with-out-str (prn results))))
      (println "Done."))))

;; ─── Output formatting ──────────────────────────────────────────

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

(defn- json-escape
  "Escape a string for JSON output."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")))

(defn- json-val
  "Convert a Clojure value to its JSON string representation."
  [v]
  (cond
    (inst? v)    (let [inst (java.time.Instant/ofEpochMilli (.getTime ^java.util.Date v))]
                   (str "\"" (.toString inst) "\""))
    (nil? v)     "null"
    (string? v)  (str "\"" (json-escape v) "\"")
    (number? v)  (str v)
    (keyword? v) (str "\"" (name v) "\"")
    (coll? v)    (str "[" (str/join "," (map json-val v)) "]")
    :else        (str "\"" (json-escape (str v)) "\"")))

(defn- to-json [results]
  (str "[\n  "
       (str/join ",\n  "
                 (for [row results]
                   (str "{"
                        (str/join ", "
                                  (for [[k v] row]
                                    (str "\"" (name k) "\": " (json-val v))))
                        "}")))
       "\n]"))

;; ─── Threads command ─────────────────────────────────────────

(defn- get-threads-spec []
  [["-t" "--thread-id ID" "Show specific thread" :id :thread-id]
   ["-p" "--participant ADDR" "Find threads by participant" :id :participant]
   ["-s" "--search TERM" "Search threads by subject" :id :search]
   ["-n" "--limit N" "Max results"
    :default 20
    :parse-fn #(Integer/parseInt %)
    :validate [#(pos? %) "Must be positive"]]
   ["--format FMT" "Output format: table | edn | json"
    :default "table"
    :validate [#(#{:table :edn :json} (keyword %)) "Must be table, edn, or json"]]
   ["-h" "--help"]])

(defn- threads-cmd [conn args]
  (let [{:keys [options errors summary]} (parse-opts args (get-threads-spec))]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (:help options)
      (println "Usage: takeout threads [options]\n")
      (println "  List and explore email threads.\n")
      (println summary)
      (System/exit 0))
    (let [db (d/db conn)]
      (cond
        (:thread-id options)
        (let [thread-id (:thread-id options)
              emails (db/get-thread-emails db thread-id)]
          (println "Thread:" thread-id)
          (println "Emails:" (count emails))
          (doseq [e emails]
            (println "  From:" (:email/from e))
            (println "  Date:" (:email/date e))
            (println "  Subject:" (:email/subject e))
            (println)))
        
        (:participant options)
        (let [threads (db/get-threads-by-participant db (:participant options))]
          (println "Threads involving" (:participant options) ":" (count threads))
          (doseq [tid threads]
            (let [summary (db/get-thread-summary db tid)]
              (println "  -" (:subject summary) "(" (:email-count summary) "emails)"))))
        
        (:search options)
        (let [threads (db/search-threads-by-subject db (:search options))]
          (println "Found" (count threads) "threads matching" (pr-str (:search options)))
          (doseq [tid threads]
            (let [summary (db/get-thread-summary db tid)]
              (println "  -" (:subject summary) "(" (:email-count summary) "emails)"))))
        
        :else
        (let [threads (take (:limit options) (db/get-recent-threads db 100))]
          (println "Recent threads:")
          (doseq [t threads]
            (println (format "  %-50s %3d emails  %s"
                             (:subject t) (:email-count t) (str (:last-date t))))))))))

;; ─── Dispatch ───────────────────────────────────────────────────

(def commands
  {"ingest" {:fn #'ingest-cmd :doc "Load MBOX files into the database"}
   "query"  {:fn #'query-cmd  :doc "Search emails by criteria"}
   "stats"  {:fn #'stats-cmd  :doc "Show DB summary statistics"}
   "export" {:fn #'export-cmd :doc "Dump emails as JSON/EDN"}
   "threads" {:fn #'threads-cmd :doc "List and explore email threads"}})

(defn print-help
  "Print top-level usage."
  []
  (println "takeout - Google Takeout MBOX ingestion & search\n")
  (println "Usage: takeout [global-options] <command> [command-options]\n")
  (println "Global options:")
  (println "  -d, --db PATH      Datalevin DB path      [default: ./emails.db]")
  (println "  -h, --help\n")
  (println "Commands:")
  (doseq [[name info] (sort commands)]
    (println (format "  %-10s %s" name (:doc info))))
  (println "\nRun 'takeout <command> --help' for command-specific options."))

(defn dispatch
  "Parse global options, open DB, dispatch to command handler.
   Main entry point for the CLI."
  [args]
  (let [{:keys [options arguments errors]} (parse-opts args (get-global-spec)
                                                       :in-order true)]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when (or (:help options) (empty? arguments))
      (print-help)
      (System/exit (if (:help options) 0 1)))
    (let [cmd-name (first arguments)
          cmd-args (rest arguments)
          cmd-info (get commands cmd-name)]
      (if-not cmd-info
        (do (println (str "Unknown command: " cmd-name))
            (println "Run 'takeout --help' for available commands.")
            (System/exit 1))
        (let [db-path (:db options)
              conn    (db/create-conn db-path)]
          (try
            ((:fn cmd-info) conn cmd-args)
            (finally
              (db/close-conn conn))))))))
