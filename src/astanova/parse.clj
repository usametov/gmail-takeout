(ns astanova.parse
  "MBOX parsing and email structuring using Apache Mime4j + Jakarta Mail."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as timbre :refer [warn]]
            [taoensso.timbre.appenders.core :as appenders]
            [hickory.core :as hickory :refer [as-hickory]]
            [clojure.edn :as edn])
  (:require [slingshot.slingshot :refer [try+ throw+]])
  (:import [org.apache.james.mime4j.mboxiterator MboxIterator]
           [jakarta.mail MessagingException]
           [jakarta.mail Session]
           [jakarta.mail.internet MimeMessage MimeMultipart InternetAddress]
           [jakarta.mail Part]
           [java.io ByteArrayInputStream]
           [java.nio.charset Charset]
           [java.util Properties]))

(timbre/merge-config!
 {:appenders {:spit (appenders/spit-appender {:fname "./takeout.log"})}})

(defmacro with-context
  "Accumulate structured context as errors propagate up."
  [ctx & body]
  `(try+
     ~@body
     (catch Object e#
       (throw+ (merge ~ctx e#)
               (:throwable ~'&throw-context)))))

;; ─── MBOX file -> seq of raw messages ───────────────────────────

(defn- parse-from-line
  "Extract the Gmail message ID from an mbox From_ line.
   Format: From <gmail-message-id>@xxx <day> <month> <dd> <time> <tz> <year>
   Returns the numeric Gmail message ID string, or nil.

   Example: (parse-from-line from-line-with-gmail-id) returns 1869988106885977363"
  [from-line]
  (when from-line
    (when-let [envelope (second (str/split from-line #" "))]
      (first (str/split envelope #"@")))))

(defn- extract-from-lines
  "Read an MBOX file and collect all `From_` envelope lines.
   These contain the Gmail message ID before the @xxx.
   Returns a vector of From_ line strings."
  [mbox-path]
  (let [f (io/file mbox-path)]
    (with-open [rdr (io/reader f)]
      (doall
        (filter #(.startsWith % "From ")
                (line-seq rdr))))))

(defn mbox-messages
  "Given a path to an MBOX file, return a lazy seq of maps.
   Each map has keys:
     :from-line   — the mbox `From_` envelope line (contains Gmail message ID)
     :raw-message — CharBufferWrapper with the raw email headers + body

   Uses Mime4j's MboxIterator internally (memory-efficient, one-at-a-time).

   NOTE: MboxIterator uses FileChannel.map internally, which limits it to
   files smaller than ~2 GB.  For larger files, use the `split` command
   first (see `takeout split --help`)."
  [mbox-path]
  (let [f (io/file mbox-path)
        from-lines (extract-from-lines mbox-path)
        msg-iter (-> (MboxIterator/fromFile f)
                     (.charset (Charset/forName "UTF-8"))
                     (.maxMessageSize (* 50 1024 1024))
                     (.build)
                     (.iterator))]
    (map (fn [from-line raw-msg]
           {:from-line    from-line
            :raw-message  raw-msg})
         from-lines
         (iterator-seq msg-iter))))

;; ─── Header helpers ─────────────────────────────────────────────

(defn- safe-header
  "Safely extract a header value from a MimeMessage, returning nil on failure."
  [^MimeMessage msg ^String name]
  (try
    (some-> (.getHeader msg name)
            first)
    (catch MessagingException e
      (warn e "Couldn't read header" name msg)
      nil)))

(defn parse-gmail-labels
  "Extract Gmail labels from the X-Gmail-Labels header (Google Takeout specific).
   Returns a vector of label strings, or empty vector if not found."
  [^MimeMessage msg]
  (try
    (let [header-vals (.getHeader msg "X-Gmail-Labels")]
      (if header-vals
        (->> header-vals
             (mapcat #(str/split % #","))
             (map #(str/replace % #"\r?\n" ""))
             (map str/trim)
             (remove str/blank?)
             (vec))
        []))
    (catch MessagingException e
      (warn e "Couldn't read X-Gmail-Labels header" msg)
      [])))

;; ─── Address parsing ────────────────────────────────────────────

(defn- address->str
  "Convert an InternetAddress to a string, handling nil."
  [addr]
  (when addr
    (try (.getAddress ^InternetAddress addr)
         (catch Exception e
           (warn e "Couldn't extract address from" addr)
           (str addr)))))

(defn- addresses->seq
  "Convert an array of Address to a seq of strings."
  [addresses]
  (when addresses
    (keep address->str addresses)))

;; ─── HTML cleaning ──────────────────────────────────────────────

(defn- extract-text-from-hickory
  "Recursively extract plain text from a Hickory node tree.
   Handles both strings (text nodes) and maps (element nodes).
   Adds newlines after block-level elements."
  [node]
  (cond
    (string? node) node
    (map? node)
    (case (:type node)
      :element
      (if (= :br (:tag node))
        "\n"
        (let [block? #{:p :div :li :h1 :h2 :h3 :h4 :h5 :h6 :tr :td :th :blockquote :pre}
              inner (apply str (keep extract-text-from-hickory (:content node)))]
          (if (block? (:tag node))
            (if (seq (:content node))
              (str "\n" inner "\n")
              "\n")
            inner)))
      nil)
    :else nil))

(defn html->text
  "Convert HTML to plain text using Hickory.
   Returns plain text suitable for storage and FTS indexing."
  [^String html]
  (when html
    (try
      (->> (hickory/parse-fragment html)
           (map as-hickory)
           (keep extract-text-from-hickory)
           (apply str)
           (#(str/replace % #"\u00a0" " "))
           (#(str/replace % #"\n{3,}" "\n\n"))
           str/trim)
      (catch Exception e
        (warn e "Couldn't parse HTML for text extraction")
        nil))))

;; ─── Body extraction (walk MIME tree) ───────────────────────────

(defn- get-content-type
  "Get the MIME content-type string, lowercased, from a Part."
  [^Part part]
  (try
    (some-> (.getContentType part) str/lower-case)
    (catch Exception e
      (warn e "Couldn't get content-type from" part)
      "text/plain")))

(defn- input-stream->string
  "Read an InputStream into a string. Throws on decode errors."
  [is]
  (with-open [r (java.io.BufferedReader.
                  (java.io.InputStreamReader. is "UTF-8"))]
    (->> (line-seq r)
         (str/join "\n"))))

;; ─── Jakarta Mail → immutable Clojure maps ────────────────────

(declare read-part)

(defn- read-part-body
  "Decode body text from a Part. Returns string or nil for binary.
   Throws ::body-read on decode failure with rich context."
  [^jakarta.mail.Part part ct]
  (when (and ct (or (str/starts-with? ct "text/")
                    (str/starts-with? ct "message/")))
    (try
      (let [c (.getContent part)]
        (cond (string? c) c
              (instance? java.io.InputStream c) (input-stream->string c)
              :else (str c)))
      (catch Exception e
        (throw+ {:type ::body-read
                 :content-type ct
                 :filename (try (.getFileName part) (catch Exception _ nil))
                 :disposition (try (.getDisposition part) (catch Exception _ nil))}
                e)))))

(defn- read-part-children
  "Recursively convert child parts of a multipart to Clojure maps."
  [^jakarta.mail.Part part ct]
  (when (and ct (str/starts-with? ct "multipart/"))
    (try
      (let [content (.getContent part)]
        (when (instance? MimeMultipart content)
          (mapv (fn [i]
                  (read-part (.getBodyPart ^MimeMultipart content i)))
                (range (.getCount ^MimeMultipart content)))))
      (catch Exception e
        (throw+ {:type ::children-error :content-type ct} e)))))

(defn- read-part
  "Convert a Jakarta Mail Part or MimeMessage into an immutable Clojure map.
   This is the only place that touches Jakarta Mail after this point.
   Returns {:content-type, :filename, :disposition, :body, :children}."
  [^jakarta.mail.Part part]
  (let [ct (get-content-type part)]
    (try
      (let [body     (read-part-body part ct)
            children (read-part-children part ct)]
        {:content-type ct
         :filename    (try (.getFileName part) (catch Exception _ nil))
         :disposition (try (.getDisposition part) (catch Exception _ nil))
         :body        body
         :children    children})
      (catch Exception e
        (throw+ {:type ::read-part
                 :content-type ct
                 :filename (try (.getFileName part) (catch Exception _ nil))}
                e)))))

;; ─── Body extraction (works on immutable maps) ─────────────────

(def ^:dynamic *debug*
  "When true, log body extraction decisions to *err*."
  false)

(def ^:dynamic *log-writer*
  "When set, write debug MIME tree info to this java.io.Writer."
  nil)

(declare clean-body)

(defn- clean-body
  "Strip MIME boundary markers, Content-Type/Transfer-Encoding headers,
   and other encoding artifacts from body text."
  [s]
  (when s
    (-> s
        (str/replace #"(?m)^Content-(Type|Transfer-Encoding|Disposition|ID|Description):.*$" "")
        (str/replace #"(?m)^X-[^:]+:.*$" "")
        (str/replace #"(?m)^MIME-Version:.*$" "")
        (str/replace #"^-{2,}[^\n]*-{2,}\n?" "")
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

(defn- log-mime-tree
  "Print the MIME tree structure with body lengths to *err* and
   *log-writer* (if set). msg-id is included for correlation with DB."
  ([part-map msg-id] (log-mime-tree part-map msg-id 0))
  ([part-map msg-id depth]
   (let [indent (apply str (repeat (* depth 2) " "))
         ct     (:content-type part-map)
         b      (:body part-map)
         blen   (count (str b))
         kids   (:children part-map)
         line   (str indent "- " ct " | body: " blen " chars | kids: " (count kids))]
     (binding [*out* *err*]
       (println line))
     (when-let [w *log-writer*]
       (try
         (when (zero? depth)
           (.write w (str "--- MIME tree for " msg-id " ---\n")))
         (.write w (str line "\n"))
         (when (and (zero? depth) (seq kids))
           (.write w "\n"))
         (catch Exception e
           (warn e "Couldn't write MIME tree log")))
       (doseq [k kids]
         (log-mime-tree k msg-id (inc depth)))))))

(defn- extract-text-from-part
  "Recursively extract plain text from a parsed MIME part map.
   Returns decoded string or nil."
  [part-map]
  (let [ct (:content-type part-map)]
    (when *debug*
      (binding [*out* *err*]
        (println "--- MIME tree ---")
        (log-mime-tree part-map "(msg-id not yet known)")
        (println "-----------------")))
    (cond
      (and ct (str/starts-with? ct "text/plain"))
      (:body part-map)

      (and ct (str/starts-with? ct "text/html"))
      (some-> (:body part-map) html->text)

      (and ct (or (str/starts-with? ct "multipart/")
                  (str/starts-with? ct "message/rfc822")))
      (some extract-text-from-part (:children part-map))

      :else
      (do (when *debug*
            (binding [*out* *err*]
              (println "  skipping unknown content-type:" ct)))
          nil))))

(defn extract-text-body
  "Extract the plain-text body from a parsed MIME part map.
   Prefers text/plain over text/html, skips non-text parts."
  [part-map]
  (or (clean-body (extract-text-from-part part-map)) ""))

(defn extract-html-body
  "Extract the HTML body from a parsed MIME part map.
   Returns nil if no HTML part found."
  [part-map]
  (letfn [(find-html [m]
            (let [ct (:content-type m)]
              (cond
                (and ct (str/starts-with? ct "text/html"))
                (:body m)

                (and ct (str/starts-with? ct "multipart/"))
                (some find-html (:children m))

                :else nil)))]
    (find-html part-map)))

(defn extract-attachments
  "Walk the MIME tree and collect attachment metadata.
   Returns a vector of EDN strings, each describing one attachment:
     {:filename \"report.pdf\" :content-type \"application/pdf\" :size 12345}
   Returns empty vector if no attachments found."
  [part-map]
  (let [attachments (filter (fn [m]
                              (or (= "attachment" (:disposition m))
                                  (and (not (str/includes? (:content-type m) "text/"))
                                       (not (str/includes? (:content-type m) "multipart/"))
                                       (some? (:filename m)))))
                            (tree-seq (fn [m] (seq (:children m))) :children part-map))]
    (mapv (fn [m]
            (pr-str {:filename     (:filename m)
                     :content-type (:content-type m)
                     :size         -1}))
          attachments)))

;; ─── Raw message -> structured email map ────────────────────────

(defn- generate-fallback-id
  "Generate a deterministic fallback ID when Message-ID is missing.
   Uses content hash to ensure same email gets same ID (for dedup)."
  [subject from date body]
  (let [safe-body (if (string? body) (subs body 0 (min 200 (count body))) "")
        content (str (or subject "") "|" (or from "") "|" (or date "") "|" safe-body)
        hash-bytes (-> (java.security.MessageDigest/getInstance "SHA-256")
                       (.digest (.getBytes content "UTF-8")))]
    (format "fallback-%064x" (BigInteger. 1 hash-bytes))))

(defn parse-raw-message
  "Parse a raw email into a structured email map using Jakarta Mail.

   raw-msg can be either:
   - A map with keys :from-line and :raw-message (from mbox-messages)
   - A CharBufferWrapper (backward compatible, no From_ line)

   Returns a map with keys: :message-id, :subject, :from, :to, :cc, :date,
   :body, :html, :thread-id, :labels, :attachments

   When a :from-line is provided, the Gmail message ID from the mbox
   envelope is used as the primary :message-id, falling back to the
   standard Message-ID header, then to a content-hash fallback."
  [raw-msg]
  (try+
    (let [{:keys [from-line raw-message] :or {raw-message raw-msg}}
          (if (map? raw-msg) raw-msg {:raw-message raw-msg})
          raw-str   (str/triml (str raw-message))
          input     (ByteArrayInputStream. (.getBytes raw-str "UTF-8"))
          props     (doto (Properties.)
                      (.setProperty "mail.mime.address.strict" "false"))
          session   (Session/getDefaultInstance props)
          msg       (MimeMessage. session input)
          part-map  (read-part msg)
          gmail-id  (parse-from-line from-line)
          msg-id    (or (safe-header msg "Message-ID")
                        (generate-fallback-id
                         (.getSubject msg)
                         (let [from (.getFrom msg)]
                           (when (seq from) (.getAddress (first from))))
                         (.getSentDate msg)
                         (extract-text-body part-map)))
          txt       (extract-text-body part-map)
          atts      (extract-attachments part-map)]
      {:message-id  msg-id
       :gmail-id    gmail-id
       :subject     (try (.getSubject msg) (catch Exception _ nil))
       :from        (try
                      (let [from (.getFrom msg)]
                        (when (seq from) (address->str (first from))))
                      (catch Exception _ nil))
       :to          (try (addresses->seq (.getRecipients msg jakarta.mail.Message$RecipientType/TO)) (catch Exception _ nil))
       :cc          (try (addresses->seq (.getRecipients msg jakarta.mail.Message$RecipientType/CC)) (catch Exception _ nil))
       :date        (try (.getSentDate msg) (catch Exception _ nil))
       :body        (if (and (zero? (count txt)) (seq atts))
                      (str/join "\n" (mapv (fn [att]
                                             (let [m (try (edn/read-string att) (catch Exception _ {:filename att :content-type ""}))]
                                               (str "[Attachment: " (:filename m) " (" (first (str/split (:content-type m) #";")) ")]")))
                                           atts))
                      txt)
       :html        (extract-html-body part-map)
       :thread-id   (or (safe-header msg "Thread-Topic")
                        (safe-header msg "References")
                        (safe-header msg "X-GM-THRID"))
       :labels      (parse-gmail-labels msg)
       :attachments atts})
    (catch [:type ::body-read] {:keys [content-type filename disposition] :as m}
      (let [raw-str   (str/triml (str raw-msg))
            from-line (first (str/split-lines (str raw-msg)))]
        (warn (:throwable &throw-context)
              "Couldn't read body"
              {:content-type content-type
               :filename filename
               :disposition disposition
               :from-line from-line
               :preview (subs raw-str 0 (min 200 (count raw-str)))}))
      nil)
    (catch Object e
      (let [raw-str   (str/triml (str raw-msg))
            from-line (first (str/split-lines (str raw-msg)))
            preview   (subs raw-str 0 (min 200 (count raw-str)))]
        (warn (:throwable &throw-context)
              "Failed to parse email"
              {:from-line from-line
               :preview preview}))
      nil)))

;; ─── Batch ingestion ─────────────────────────────────────────────

(defn parse-mbox
  "Parse an entire MBOX file, returning a lazy seq of structured email maps.
   Each map has keys: :message-id, :subject, :from, :to, :cc, :date,
   :body, :html, :thread-id, :labels, :attachments"
  [mbox-path]
  (->> (mbox-messages mbox-path)
       (map (fn [raw]
              (try+
                (parse-raw-message raw)
                (catch Object e
                  (warn (:throwable &throw-context)
                        "Iterator error parsing raw message")
                  nil))))
       (filter some?)  ;; Remove nil results from parse errors
       (filter :message-id)))  ;; Ensure all emails have an ID



