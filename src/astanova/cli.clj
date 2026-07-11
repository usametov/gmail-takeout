(ns astanova.cli
  "Command-line interface: argument parsing, dispatch, formatting.
   Uses babashka/cli for option parsing and command dispatch."
  (:require [astanova.db :as db]
            [astanova.ingest :as ingest]
            [astanova.parse :as parse]
            [babashka.cli :as cli]
            [clojure.string :as str]
            [datalevin.core :as d])
  (:import [java.time Instant LocalDate ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(declare build-query-clauses build-query print-table to-json apply-limit-offset paginated-results)

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
                       :ex-msg (fn [_] "Batch size must be positive")}}})

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
      (doseq [f paths]
        (let [r (ingest/ingest-mbox! conn (.getAbsolutePath f) source :batch-size bsize)]
          (println (format "  %-40s  %6d emails  %3d txs"
                           (.getName f) (:email-count r) (:tx-count r)))))
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
            {:keys [results page-results offset limit] :as p} (paginated-results results opts)
            fmt     (keyword (:format opts))]
        (case fmt
          :table (print-table page-results)
          :edn   (prn {:total total
                       :offset offset
                       :limit limit
                       :results page-results})
          :json  (println (to-json page-results))))
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
          :json (spit output (to-json results))
          :edn  (spit output (with-out-str (prn results))))
        (println "Done."))
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
          :edn (prn {:count (count sorted) :labels sorted})
          :json (println (to-json {:labels sorted :count (count sorted)}))))
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
          :edn (prn {:count (count sorted) :addresses sorted})
          :json (println (to-json {:addresses sorted :count (count sorted)}))))
      (finally
        (db/close-conn conn)))))

;; ─── Query building ────────────────────────────────────────────

(defn- build-query-clauses [opts]
  (let [labels-str    (:labels opts)
        labels        (when labels-str (str/split labels-str #","))
        labels-mode   (keyword (:labels-mode opts "any"))
        text          (:text opts)]
    (cond-> []
      labels
      (into (case labels-mode
              :any  (list (into [:or]
                                (for [l labels]
                                  ['?e :email/labels l])))
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
                                  ['?e :email/body '?txt])
                            [(list 'clojure.string/includes?
                                   (list 'clojure.string/lower-case '?txt)
                                   (clojure.string/lower-case text))])
      (:since opts)   (conj ['?e :email/date '?d]
                            [(list '>= '?d (parse-date (:since opts)))])
      (:before opts)  (conj ['?e :email/date '?d]
                            [(list '< '?d (parse-date (:before opts)))])
      (empty? (select-keys opts [:subject :from :to :address :labels :text :since :before]))
      (conj ['?e :email/subject]))))

(defn- build-query [clauses]
  (let [pull-pattern [:email/subject :email/from :email/to
                      :email/date :email/labels :email/body]]
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

(defn- json-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")))

(defn- json-val [v]
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

;; ─── Split command (zero-copy NIO splitter) ─────────────────────

(defn- is-from-start?
  "Check if 'From ' at position idx in text is at the start of a line
   and looks like a real mbox delimiter (has an @ on the same line).
   This rejects false positives in binary/Base64 attachment data."
  [^String text ^long idx]
  (and (or (zero? idx)
           (let [prev (dec idx)]
             (when (>= prev 0)
               (let [ch (.charAt text prev)]
                 (or (= ch \newline) (= ch \return))))))
       ;; Verify this looks like a real mbox From_ line by checking for @
       (let [line-end (str/index-of text "\n" idx)]
         (if line-end
           (str/includes? (subs text idx line-end) "@")
           (str/includes? (subs text idx) "@")))))

(defn- find-from-in-buffer
  "Search text for 'From ' at start of line. Returns absolute byte pos or nil."
  [^String text ^long buf-pos]
  (loop [idx (str/index-of text "From ")]
    (when idx
      (if (is-from-start? text idx)
        (+ buf-pos idx)
        (recur (str/index-of text "From " (+ idx 5)))))))

(defn- find-next-from-boundary
  "Returns byte position of the next 'From ' at start of line, or nil."
  [^java.io.RandomAccessFile raf ^long start-pos ^long max-lookahead]
  (let [file-len (.length raf)
        buf-size 16384]
    (.seek raf start-pos)
    (loop [pos start-pos]
      (when (and (< (- pos start-pos) max-lookahead)
                 (< pos file-len))
        (let [to-read (int (min buf-size (- file-len pos)))
              buf (byte-array to-read)
              n (.read raf buf 0 to-read)]
          (when (pos? n)
            (let [text (String. buf 0 n "UTF-8")]
              (or (find-from-in-buffer text pos)
                  (recur (+ pos n))))))))))

(defn- copy-range
  [^java.nio.channels.FileChannel src ^java.io.File dest ^long start ^long end]
  (.position src start)
  (let [size (- end start)
        opts (into-array java.nio.file.StandardOpenOption
                         [java.nio.file.StandardOpenOption/CREATE
                          java.nio.file.StandardOpenOption/WRITE
                          java.nio.file.StandardOpenOption/TRUNCATE_EXISTING])]
    (with-open [dst (java.nio.channels.FileChannel/open (.toPath dest) opts)]
      (.transferFrom dst src 0 size))))

(defn split-mbox!
  "Split mbox with smart 'From ' boundary detection for safe message alignment.
   Both start and end of each chunk are aligned to 'From ' at start of line,
   ensuring every chunk is a valid mbox file that MboxIterator can read."
  [^String mbox-path ^long chunk-size-mb ^String out-dir]
  (let [chunk-size     (* chunk-size-mb 1024 1024)
        lookahead-bytes (* 8 1024 1024)
        stem (-> (java.io.File. mbox-path) .getName (str/replace #"\.mbox$" ""))]
    (.mkdirs (java.io.File. out-dir))
    (with-open [raf (java.io.RandomAccessFile. mbox-path "r")
                src-channel (.getChannel raf)]
      (let [total (.length raf)]
        (loop [chunk-idx 0
               start-pos 0]
          (when (< start-pos total)
            (let [approx-end (+ start-pos chunk-size)
                  ;; Find the next 'From ' boundary after approx-end to use as chunk end
                  end-pos (if (>= approx-end total)
                            total
                            (or (find-next-from-boundary raf approx-end lookahead-bytes)
                                total))
                  out-file (java.io.File. (format "%s/%s.part-%04d.mbox"
                                                  out-dir stem (inc chunk-idx)))]
              (when (< start-pos end-pos)
                (println (format "  Writing chunk %d (%d MB target):" (inc chunk-idx) chunk-size-mb))
                (println (format "    %s" (.getName out-file)))
                (copy-range src-channel out-file start-pos end-pos)
                (println (format "    %d bytes" (- end-pos start-pos))))
              (recur (inc chunk-idx) end-pos))))))))


(defn split-cmd [{:keys [opts args]}]
  (let [chunk-size-mb (or (:size opts) 500)
        out-dir (:output opts)
        ;; babashka/cli dispatch skips option parsing when positional args
        ;; are present (::dispatch-tree). Filter out -o/--output and
        ;; their values from args since already parsed into :output.
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
            (split-mbox! (.getAbsolutePath f) chunk-size-mb target-dir)))))))


;; ─── Dispatch ───────────────────────────────────────────────────

(def cli-tree
  "Command dispatch table for babashka/cli."
  [{:cmds [] :spec global-spec}
   {:cmds ["ingest"]  :fn ingest-cmd  :spec ingest-spec  :doc "Load MBOX files into the database"}
   {:cmds ["query"]   :fn query-cmd   :spec query-spec   :doc "Search emails by criteria"}
   {:cmds ["stats"]   :fn stats-cmd                      :doc "Show DB summary statistics"}
   {:cmds ["export"]  :fn export-cmd  :spec export-spec  :doc "Dump emails as JSON/EDN"}
   {:cmds ["threads"] :fn threads-cmd :spec threads-spec :doc "List and explore email threads"}
   {:cmds ["split"]   :fn split-cmd   :spec split-spec   :doc "Split large MBOX files into smaller chunks"}
   {:cmds ["labels"]  :fn labels-cmd  :spec labels-spec  :doc "List all email labels"}
   {:cmds ["addresses"] :fn addresses-cmd :spec addresses-spec :doc "List all email addresses"}])
