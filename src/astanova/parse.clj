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
  "Strip HTML tags, returning plain text. Uses a simple regex approach;
   for production use, consider Tika's HTML parsing via AutoDetectParser."
  [^String html]
  (when html
    (-> html
        (str/replace #"(?i)<br\s*/?>" "\n")
        (str/replace #"(?i)<p[^>]*>" "\n")
        (str/replace #"(?i)</p>" "\n")
        (str/replace #"(?i)<[^>]+>" "")
        (str/replace #"&nbsp;" " ")
        (str/replace #"&amp;" "&")
        (str/replace #"&lt;" "<")
        (str/replace #"&gt;" ">")
        (str/replace #"&quot;" "\"")
        (str/replace #"&#(\d+);"
                     (fn [m] (str (char (Long/parseLong (second m))))))
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

;; ─── Body extraction (walk MIME tree) ───────────────────────────

(defn- get-content-type
  "Get the MIME content-type string, lowercased, from a Part."
  [^Part part]
  (try
    (-> (.getContentType part)
        (or "text/plain")
        str/lower-case)
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

(defn- extract-body-from-part
  "Iteratively extract body content from a MIME part, handling multipart etc.
   Uses loop/recur with an explicit stack to avoid stack overflow
   on deeply nested MIME structures.  Uses string-based content-type
   checks (get-content-type) instead of Jakarta Mail's .isMimeType
   to avoid triggering a recursive parser bug in ParameterList/HeaderTokenizer."
  [^Part part]
  (try
    (loop [stack (list part)
           texts (transient [])]
      (if-let [p (first stack)]
        (let [rest-stack (rest stack)
              ct        (get-content-type p)]
          (cond
            ;; Multipart: push sub-parts onto the stack (in reverse order
            ;; so they are processed left-to-right)
            (str/starts-with? ct "multipart/")
            (let [content (try (.getContent p) (catch Throwable _ nil))]
              (if (instance? MimeMultipart content)
                (let [mp    ^MimeMultipart content
                      n     (.getCount mp)
                      parts (mapv #(.getBodyPart mp %) (range n))]
                  (recur (into rest-stack (rseq parts)) texts))
                (recur rest-stack
                       (conj! texts
                              (cond
                                (string? content) content
                                (instance? java.io.InputStream content) (input-stream->string content)
                                :else (str content))))))

            ;; Text parts: collect content
            (or (str/starts-with? ct "text/plain")
                (str/starts-with? ct "text/html"))
            (let [content (try (.getContent p) (catch Throwable _ nil))]
              (recur rest-stack
                     (conj! texts
                            (cond
                              (string? content) content
                              (instance? java.io.InputStream content) (input-stream->string content)
                              :else (str content)))))

            ;; Other parts: try to get content as-is
            :else
            (let [content (try (.getContent p) (catch Throwable _ nil))]
              (recur rest-stack
                     (conj! texts
                            (if (instance? java.io.InputStream content)
                              (input-stream->string content)
                              (str content)))))))
        ;; Stack exhausted — join all collected texts
        (str/join "\n" (persistent! texts))))
    (catch Throwable _ "")))

(defn extract-text-body
  "Extract the plain-text body from a MimeMessage, preferring text/plain over text/html.
   Uses string-based content-type checks (get-content-type) instead of Jakarta Mail's
   .isMimeType to avoid triggering a recursive parser bug in ParameterList/HeaderTokenizer."
  [^MimeMessage msg]
  (try
    (let [ct (get-content-type msg)]
      (cond
        (str/starts-with? ct "text/plain")
        (let [content (.getContent msg)]
          (cond
            (string? content) content
            (instance? java.io.InputStream content) (input-stream->string content)
            :else (str content)))

        (str/starts-with? ct "text/html")
        (let [content (.getContent msg)]
          (html->text (cond
                        (string? content) content
                        (instance? java.io.InputStream content) (input-stream->string content)
                        :else (str content))))

        (str/starts-with? ct "multipart/")
        (let [content (.getContent msg)]
          (if (instance? MimeMultipart content)
            (let [n (.getCount ^MimeMultipart content)
                  parts (for [i (range n)]
                          (.getBodyPart ^MimeMultipart content i))
                  text-parts (filter #(str/includes? (get-content-type %) "text/plain") parts)
                  texts (map #(try 
                                 (let [c (.getContent ^Part %)]
                                   (cond
                                     (string? c) c
                                     (instance? java.io.InputStream c) (input-stream->string c)
                                     :else (str c)))
                                 (catch Throwable _ "")) text-parts)]
              (or (first texts) ""))
            (extract-body-from-part msg)))

        :else
        (extract-body-from-part msg)))
    (catch Throwable e
      (println "WARNING: Failed to extract text body:" (.getMessage e))
      "")))

(defn extract-html-body
  "Extract the HTML body from a MimeMessage. Returns nil if no HTML part found."
  [^MimeMessage msg]
  (try
    (let [ct (get-content-type msg)]
      (cond
        (str/starts-with? ct "text/html")
        (let [content (.getContent msg)]
          (cond
            (string? content) content
            (instance? java.io.InputStream content) (input-stream->string content)
            :else (str content)))

        (str/starts-with? ct "multipart/")
        (let [content (.getContent msg)]
          (if (instance? MimeMultipart content)
            (->> (range (.getCount ^MimeMultipart content))
                 (map #(.getBodyPart ^MimeMultipart content %))
                 (filter #(str/includes? (get-content-type %) "text/html"))
                 (map #(try 
                        (let [c (.getContent ^Part %)]
                          (cond
                            (string? c) c
                            (instance? java.io.InputStream c) (input-stream->string c)
                            :else (str c)))
                        (catch Throwable _ "")))
                 (first))
            nil))
        :else nil))
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
       :body        (extract-text-body msg)
       :html        (extract-html-body msg)
       :thread-id   (or (safe-header msg "Thread-Topic")
                      (safe-header msg "References")
                      (safe-header msg "X-GM-THRID"))
       :labels      (parse-gmail-labels msg)
       :attachments (extract-attachments msg)})
    (catch Exception e
      (println "WARNING: Failed to parse email:" (.getMessage e))
      nil)))

;; ─── Batch ingestion ─────────────────────────────────────────────

(defn parse-mbox
  "Parse an entire MBOX file, returning a lazy seq of structured email maps.
   Each map has keys: :message-id, :subject, :from, :to, :cc, :date,
   :body, :html, :thread-id, :labels, :attachments"
  [mbox-path]
  (->> (mbox-messages mbox-path)
       (map parse-raw-message)
       (filter some?)  ;; Remove nil results from parse errors
       (filter :message-id)))  ;; Ensure all emails have an ID



