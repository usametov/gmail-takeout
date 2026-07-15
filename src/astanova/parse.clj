(ns astanova.parse
  "MBOX parsing and email structuring using Apache Mime4j + Jakarta Mail."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.apache.james.mime4j.mboxiterator MboxIterator]
           [jakarta.mail Session]
           [jakarta.mail.internet MimeMessage MimeMultipart InternetAddress]
           [jakarta.mail Part]
           [java.io ByteArrayInputStream]
           [java.nio.charset Charset]
           [java.util Properties]))

;; ─── MBOX file -> seq of raw messages ───────────────────────────

(defn mbox-messages
  "Given a path to an MBOX file, return a lazy seq of CharBufferWrappers
   using Mime4j's MboxIterator (memory-efficient, one-at-a-time).

   NOTE: MboxIterator uses FileChannel.map internally, which limits it to
   files smaller than ~2 GB.  For larger files, use the `split` command
   first (see `takeout split --help`)."
  [mbox-path]
  (let [f (io/file mbox-path)]
    (-> (MboxIterator/fromFile f)
        (.charset (Charset/forName "UTF-8"))
        (.maxMessageSize (* 50 1024 1024))   ; 50 MB max per message
        (.build)
        (.iterator)
        iterator-seq)))

;; ─── Header helpers ─────────────────────────────────────────────

(defn- safe-header
  "Safely extract a header value from a MimeMessage, returning nil on failure."
  [^MimeMessage msg ^String name]
  (try
    (let [vals (.getHeader msg name)]
      (when vals
        (first vals)))
    (catch Exception _ nil)))

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
    (catch Exception _ [])))

;; ─── Address parsing ────────────────────────────────────────────

(defn- address->str
  "Convert an InternetAddress to a string, handling nil."
  [addr]
  (when addr
    (try (.getAddress ^InternetAddress addr)
         (catch Exception _ (str addr)))))

(defn- addresses->seq
  "Convert an array of Address to a seq of strings."
  [addresses]
  (when addresses
    (keep address->str addresses)))

;; ─── HTML cleaning ──────────────────────────────────────────────

(defn html->text
  "Strip HTML tags, removing style/script blocks and inline Base64 data URIs.
   Returns plain text suitable for storage and FTS indexing."
  [^String html]
  (when html
    (-> html
        ;; Remove style and script blocks entirely (they often contain Base64)
        (str/replace #"(?si)<style[^>]*>.*?</style>" "")
        (str/replace #"(?si)<script[^>]*>.*?</script>" "")
        ;; Remove data: URIs (inline images, fonts, etc.)
        (str/replace #"data:[^\"'\s\)]+>?" "")
        ;; Convert line break tags to newlines
        (str/replace #"(?i)<br\s*/?>" "\n")
        (str/replace #"(?i)<p[^>]*>" "\n")
        (str/replace #"(?i)</p>" "\n")
        ;; Strip all remaining HTML tags
        (str/replace #"(?i)<[^>]+>" "")
        ;; HTML entities
        (str/replace #"&nbsp;" " ")
        (str/replace #"&amp;" "&")
        (str/replace #"&lt;" "<")
        (str/replace #"&gt;" ">")
        (str/replace #"&quot;" "\"")
        (str/replace #"&#(\d+);"
                     (fn [m] (str (char (Long/parseLong (second m))))))
        ;; Collapse multiple newlines
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

;; ─── Body extraction (walk MIME tree) ───────────────────────────

(defn- get-content-type
  "Get the MIME content-type string, lowercased, from a Part."
  [^Part part]
  (try
    (some-> (.getContentType part) str/lower-case)
    (catch Exception _ "text/plain")))

(defn- input-stream->string
  "Read an InputStream into a string."
  [is]
  (try
    (with-open [reader (java.io.BufferedReader.
                      (java.io.InputStreamReader. is "UTF-8"))]
      (let [sb (StringBuilder.)]
        (loop [line (.readLine reader)]
          (if (nil? line)
            (.toString sb)
            (do
              (.append sb line)
              (.append sb "\n")
              (recur (.readLine reader)))))))
    (catch Exception _ "")))

;; ─── MIME Part protocol ─────────────────────────────────────────

(defprotocol MimePart
  "Protocol over MIME parts, hiding Jakarta Mail interop."
  (body [this] "Get decoded content of this part, or nil.")
  (content-type [this] "Get content-type string, lowercased, or nil.")
  (children [this] "Get child parts (for multipart), or nil.")
  (filename [this] "Get filename (for attachments), or nil.")
  (log-info [this] "Return a map with diagnostic info about this part."))

(defrecord JakartaPart [^jakarta.mail.Part part]
  MimePart
  (body [this]
    (try
      (let [c (.getContent part)]
        (cond (string? c) c
              (instance? java.io.InputStream c) (input-stream->string c)
              :else (str c)))
      (catch Throwable _ nil)))
  (content-type [this]
    (try (some-> (.getContentType part) str/lower-case)
         (catch Exception _ nil)))
  (children [this]
    (try
      (let [ct (.getContent part)]
        (when (instance? MimeMultipart ct)
          (mapv (fn [i] (->JakartaPart (.getBodyPart ^MimeMultipart ct i)))
                (range (.getCount ^MimeMultipart ct)))))
      (catch Throwable _ nil)))
  (filename [this]
    (try (.getFileName part) (catch Throwable _ nil)))
  (log-info [this]
    (let [ct (content-type this)]
      {:content-type ct
       :body-type (when (body this) (class (body this)))
       :body-length (count (str (body this)))
       :has-children (some? (children this))
       :child-count (count (children this))
       :filename (filename this)})))

(defn wrap-part
  "Wrap a Jakarta Mail Part or MimeMessage in a MimePart."
  [^jakarta.mail.Part p]
  (->JakartaPart p))

;; ─── Body extraction (recursive, uses MimePart protocol) ───────

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
  ([mp msg-id] (log-mime-tree mp msg-id 0))
  ([mp msg-id depth]
   (let [indent (apply str (repeat (* depth 2) " "))
         ct (content-type mp)
         b (body mp)
         blen (count (str b))
         kids (children mp)
         line (str indent "- " ct " | body: " blen " chars | kids: " (count kids))]
     (binding [*out* *err*]
       (println line))
     (when-let [w *log-writer*]
       (try
         (when (zero? depth)
           (.write w (str "--- MIME tree for " msg-id " ---\n")))
         (.write w (str line "\n"))
         (when (and (zero? depth) (seq kids))
           (.write w "\n"))
         (catch Throwable _)))
     (doseq [k kids]
       (log-mime-tree k msg-id (inc depth))))))

(defn- extract-text-from-part
  "Recursively extract plain text from a MimePart.
   Returns decoded string or nil."
  [mp]
  (try
    (let [ct (content-type mp)]
      (when *debug*
        (binding [*out* *err*]
          (println "--- MIME tree ---")
          (log-mime-tree mp "(msg-id not yet known)")
          (println "-----------------")))
      (cond
        (and ct (str/starts-with? ct "text/plain"))
        (body mp)

        (and ct (str/starts-with? ct "text/html"))
        (some-> (body mp) html->text)

        (and ct (or (str/starts-with? ct "multipart/")
                    (str/starts-with? ct "message/rfc822")))
        (let [kids (children mp)]
          (some extract-text-from-part kids))

        :else
        (do (when *debug*
              (binding [*out* *err*]
                (println "  skipping unknown content-type:" ct)))
            nil)))
    (catch Throwable t
      (when *debug*
        (binding [*out* *err*]
          (println "  extract-text-from-part error:" (.getMessage t))))
      nil)))

(defn extract-text-body
  "Extract the plain-text body from a MimeMessage.
   Prefers text/plain over text/html, skips non-text parts."
  [^MimeMessage msg]
  (or (clean-body (extract-text-from-part (wrap-part msg))) ""))

(defn extract-html-body
  "Extract the HTML body from a MimeMessage. Returns nil if no HTML part found."
  [^MimeMessage msg]
  (try
    (letfn [(find-html [mp]
              (let [ct (content-type mp)]
                (cond
                  (and ct (str/starts-with? ct "text/html"))
                  (body mp)

                  (and ct (str/starts-with? ct "multipart/"))
                  (some find-html (children mp))

                  :else nil)))]
      (find-html (wrap-part msg)))
    (catch Throwable _ nil)))

;; ─── Attachment metadata ────────────────────────────────────────

(defn extract-attachments
  "Walk the MIME tree and collect attachment metadata.
   Returns a vector of EDN strings, each describing one attachment:
     {:filename \"report.pdf\" :content-type \"application/pdf\" :size 12345}
   Returns empty vector if no attachments found."
  [^MimeMessage msg]
  (try
    (let [ct (get-content-type msg)]
      (when (str/starts-with? ct "multipart/")
        (let [content (.getContent msg)]
          (if (instance? MimeMultipart content)
            (let [mp    ^MimeMultipart content
                  n     (.getCount mp)
                  parts (for [i (range n)]
                          (.getBodyPart mp i))]
              (->> parts
                   (filter (fn [^Part p]
                             (let [disp (try (.getDisposition p) (catch Throwable _ nil))]
                               (or (= Part/ATTACHMENT disp)
                                   (and (not (str/includes? (get-content-type p) "text/"))
                                        (not (str/includes? (get-content-type p) "multipart/"))
                                        (some? (try (.getFileName p) (catch Throwable _ nil))))))))
                   (mapv (fn [^Part p]
                           (pr-str {:filename      (try (.getFileName p) (catch Throwable _ nil))
                                    :content-type  (get-content-type p)
                                    :size          (try (.getSize p) (catch Throwable _ -1))})))))
            []))))
    (catch Throwable _ [])))

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
  "Parse a raw mime4j CharBufferWrapper into a structured email map
   using Jakarta Mail for header and body extraction.
   Returns a map with keys: :message-id, :subject, :from, :to, :cc, :date,
   :body, :html, :thread-id, :labels, :attachments"
  [raw-msg]
  (try
    (let [raw-str   (str/triml (str raw-msg))
          input     (ByteArrayInputStream. (.getBytes raw-str "UTF-8"))
          props     (doto (Properties.)
                      (.setProperty "mail.mime.address.strict" "false"))
          session   (Session/getDefaultInstance props)
          msg       (MimeMessage. session input)
          msg-id    (or (safe-header msg "Message-ID")
                         (generate-fallback-id 
                           (.getSubject msg)
                           (let [from (.getFrom msg)]
                             (when (seq from) (.getAddress (first from))))
                           (.getSentDate msg)
                           (extract-text-body msg)))]
      {:message-id  msg-id
       :subject     (try (.getSubject msg) (catch Exception _ nil))
       :from        (try 
                      (let [from (.getFrom msg)]
                        (when (seq from) (address->str (first from))))
                      (catch Exception _ nil))
       :to          (try (addresses->seq (.getRecipients msg jakarta.mail.Message$RecipientType/TO)) (catch Exception _ nil))
       :cc          (try (addresses->seq (.getRecipients msg jakarta.mail.Message$RecipientType/CC)) (catch Exception _ nil))
       :date        (try (.getSentDate msg) (catch Exception _ nil))
       :body        (let [txt (extract-text-body msg)
                        atts (extract-attachments msg)]
                     (if (and (zero? (count txt)) (seq atts))
                       (str/join "\n" (mapv (fn [att]
                                               (let [m (try (clojure.edn/read-string att) (catch Exception _ {:filename att :content-type ""}))]
                                                 (str "[Attachment: " (:filename m) " (" (first (str/split (:content-type m) #";")) ")]")))
                                              atts))
                       txt))
       :html        (extract-html-body msg)
       :thread-id   (or (safe-header msg "Thread-Topic")
                      (safe-header msg "References")
                      (safe-header msg "X-GM-THRID"))
       :labels      (parse-gmail-labels msg)
       :attachments (extract-attachments msg)})
    (catch Exception e
      (let [raw-str   (str/triml (str raw-msg))
            from-line (first (str/split-lines (str raw-msg)))
            preview   (subs raw-str 0 (min 200 (count raw-str)))]
        (binding [*out* *err*]
          (println "WARNING: Failed to parse email:" (.getMessage e))
          (println "  From line:" from-line)
          (println "  Preview:" preview)))
      nil)))

;; ─── Batch ingestion ─────────────────────────────────────────────

(defn parse-mbox
  "Parse an entire MBOX file, returning a lazy seq of structured email maps.
   Each map has keys: :message-id, :subject, :from, :to, :cc, :date,
   :body, :html, :thread-id, :labels, :attachments"
  [mbox-path]
  (->> (mbox-messages mbox-path)
       (map (fn [raw]
              (try
                (parse-raw-message raw)
                (catch Throwable t
                  (binding [*out* *err*]
                    (println "CRITICAL: Iterator error:" (.getMessage t)))
                  nil))))
       (filter some?)  ;; Remove nil results from parse errors
       (filter :message-id)))  ;; Ensure all emails have an ID



