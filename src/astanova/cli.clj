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
      ;; Check for large files (> 500 MB) that may exceed MboxIterator limits
      (let [max-size (* 500 1024 1024)
            large-files (filter #(> (.length %) max-size) paths)]
        (when (seq large-files)
          (println "Warning: Some files are larger than 500 MB and may not be ingestible directly:")
          (doseq [f large-files]
            (println (format "  %s (%.1f GB)" (.getName f) (/ (.length f) 1024.0 1024.0 1024.0))))
          (println "Consider splitting them first:")
          (println "  takeout split <file> -s 500")
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
      text            (conj (list 'or ['?e :email/subject '?txt]
                                      ['?e :email/body '?txt])
                            [(list 'clojure.string/includes?
                                   (list 'clojure.string/lower-case '?txt)
                                   (clojure.string/lower-case text))])
      (:since opts)   (conj ['?e :email/date '?d]
                            [(list '>= '?d (parse-date (:since opts)))])
      (:before opts)  (conj ['?e :email/date '?d]
                            [(list '< '?d (parse-date (:before opts)))])
      (empty? (select-keys opts [:subject :from :to :labels :text :since :before]))
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

;; ─── Split command (byte-level splitter) ─────────────────────────

(def ^:private from-pattern
  "ASCII bytes for 'From ' — the mbox message delimiter."
  (byte-array (map byte "From ")))

(def ^:private from-pattern-len (int (alength from-pattern)))

(defn- find-next-from-boundary
  "Find the next 'From ' at the start of a line (preceded by \\n or \\r or at
   position 0), scanning forward from `start-pos`. Returns byte position of
   the 'F', or nil if not found within `search-limit` bytes.

   Works at the byte level — reads chunks of up to 64 KB at a time, scanning
   for the literal 'From ' bytes, then verifying the preceding character."
  [^java.io.RandomAccessFile raf start-pos search-limit]
  (let [file-len   (.length raf)
        end-pos    (min (+ start-pos search-limit) file-len)
        buf-size   65536]
    (loop [pos start-pos]
      (when (< pos end-pos)
        (let [to-read (int (min buf-size (- end-pos pos)))
              buf     (byte-array to-read)]
          (.seek raf pos)
          (.readFully raf buf 0 to-read)
          (loop [i 0]
            (if (< i to-read)
              ;; Check for 'F' followed by 'rom '
              (if (and (= (aget buf i) (byte \F))
                       (< (+ i 4) to-read)
                       (= (aget buf (inc i)) (byte \r))
                       (= (aget buf (+ i 2)) (byte \o))
                       (= (aget buf (+ i 3)) (byte \m))
                       (= (aget buf (+ i 4)) (byte \space)))
                (let [abs-pos (+ pos i)]
                  ;; Verify it's at start of a line or position 0
                  (if (or (zero? abs-pos)
                          (let [prev (if (> i 0)
                                      (aget buf (dec i))
                                      ;; newline might be in previous buffer
                                      (do (.seek raf (dec abs-pos))
                                          (.read raf)))]
                            (or (= prev (byte \newline))
                                (= prev (byte \return)))))
                    abs-pos
                    (recur (+ i 5))))  ;; false positive (e.g. >From body)
                (recur (inc i)))
              (recur (+ pos to-read)))))))))

(defn split-mbox!
  "Split an mbox file into chunks of approximately `chunk-size-mb` MB each.
   Uses byte-level seeking with RandomAccessFile — no line-by-line parsing,
   no encoding/decoding. Each chunk is aligned to a 'From ' message boundary
   (the only reliable mbox delimiter per RFC 4155)."
  [^String mbox-path ^long chunk-size-mb ^String out-dir]
  (let [chunk-size-bytes (* chunk-size-mb 1024 1024)
        search-limit     (* 4 1024 1024)   ;; 4 MB lookahead for boundary
        stem             (str/replace (.getName (java.io.File. mbox-path)) #"\.mbox$" "")
        out-dir-f        (java.io.File. out-dir)]
    (.mkdirs out-dir-f)
    (with-open [raf (java.io.RandomAccessFile. mbox-path "r")]
      (let [file-len (.length raf)]
        (loop [chunk-idx 0
               start-pos 0]
          (when (< start-pos file-len)
            (let [approx-end  (+ start-pos chunk-size-bytes)
                  ;; Find the next message boundary after approx-end
                  boundary-pos (when (< approx-end file-len)
                                 (find-next-from-boundary raf approx-end search-limit))
                  end-pos     (or boundary-pos file-len)
                  out-path    (str out-dir "/" (format "%s.part-%04d.mbox" stem (inc chunk-idx)))]
              ;; Stream raw bytes from start-pos to end-pos
              (println (format "  Writing chunk %d (%d MB target):"
                               (inc chunk-idx) chunk-size-mb))
              (println (format "    %s" out-path))
              (with-open [out (java.io.FileOutputStream. (java.io.File. out-path))]
                (let [buf-size 65536
                      buf      (byte-array buf-size)]
                  (.seek raf start-pos)
                  (loop [remaining (- end-pos start-pos)]
                    (when (pos? remaining)
                      (let [to-read (int (min buf-size remaining))
                            n (.read raf buf 0 to-read)]
                        (when (pos? n)
                          (.write out buf 0 n)
                          (recur (- remaining n))))))))
              (println (format "    %d bytes" (- end-pos start-pos)))
              (recur (inc chunk-idx) end-pos))))))))

(defn split-cmd [{:keys [opts args]}]
  (let [chunk-size-mb (:size opts)
        out-dir       (:output opts)
        paths         (mapcat (fn [p]
                                (let [f (java.io.File. p)]
                                  (if (.isDirectory f)
                                    (filter #(str/ends-with? (.getName %) ".mbox")
                                            (.listFiles f))
                                    [f])))
                              args)]
    (when (empty? paths)
      (println "No .mbox files specified.")
      (println "Usage: takeout split [options] <mbox-file>...")
      (System/exit 1))
    (doseq [mbox paths]
      (let [dir (or out-dir (.getParent mbox))]
        (println (format "Splitting %s (%d MB/chunk)..." (.getName mbox) chunk-size-mb))
        (split-mbox! (.getAbsolutePath mbox) chunk-size-mb dir)))))

;; ─── Dispatch ───────────────────────────────────────────────────

(def cli-tree
  "Command dispatch table for babashka/cli."
  [{:cmds [] :spec global-spec}
   {:cmds ["ingest"]  :fn ingest-cmd  :spec ingest-spec  :doc "Load MBOX files into the database"}
   {:cmds ["query"]   :fn query-cmd   :spec query-spec   :doc "Search emails by criteria"}
   {:cmds ["stats"]   :fn stats-cmd                      :doc "Show DB summary statistics"}
   {:cmds ["export"]  :fn export-cmd  :spec export-spec  :doc "Dump emails as JSON/EDN"}
   {:cmds ["threads"] :fn threads-cmd :spec threads-spec :doc "List and explore email threads"}
   {:cmds ["split"]   :fn split-cmd   :spec split-spec   :doc "Split large MBOX files into smaller chunks"}])