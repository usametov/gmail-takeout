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
   using Mime4j's MboxIterator (memory-efficient, one-at-a-time)."
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

(defn- extract-body-from-part
  "Recursively extract body content from a MIME part, handling multipart etc."
  [^Part part]
  (try
    (cond
      ;; Multipart: recurse into sub-parts
      (.isMimeType part "multipart/*")
      (let [content (.getContent part)]
        (if (instance? MimeMultipart content)
          (let [mp ^MimeMultipart content
                n   (.getCount mp)]
            (str/join "\n"
                      (for [i (range n)]
                        (extract-body-from-part (.getBodyPart mp i)))))
          (str content)))

      ;; Text parts: return as string
      (or (.isMimeType part "text/plain")
          (.isMimeType part "text/html"))
      (try
        (let [content (.getContent part)]
          (if (string? content) content (str content)))
        (catch Exception _ ""))

      :else
      (try (str (.getContent part)) (catch Exception _ "")))
    (catch Exception _ "")))

(defn extract-text-body
  "Extract the plain-text body from a MimeMessage, preferring text/plain over text/html."
  [^MimeMessage msg]
  (try
    (if (.isMimeType msg "text/plain")
      (str (.getContent msg))
      (if (.isMimeType msg "text/html")
        (html->text (str (.getContent msg)))
        (if (.isMimeType msg "multipart/*")
          (let [content (.getContent msg)]
            (if (instance? MimeMultipart content)
              (let [n (.getCount ^MimeMultipart content)
                    parts (for [i (range n)]
                            (.getBodyPart ^MimeMultipart content i))
                    text-parts (filter #(str/includes? (get-content-type %) "text/plain") parts)
                    texts (map #(try (str (.getContent ^Part %)) (catch Exception _ "")) text-parts)]
                (or (first texts) ""))
              (extract-body-from-part msg)))
          (extract-body-from-part msg))))
    (catch Exception e
      "")))

(defn extract-html-body
  "Extract the HTML body from a MimeMessage. Returns nil if no HTML part found."
  [^MimeMessage msg]
  (try
    (cond
      (.isMimeType msg "text/html")
      (str (.getContent msg))

      (.isMimeType msg "multipart/*")
      (let [content (.getContent msg)]
        (if (instance? MimeMultipart content)
          (->> (range (.getCount ^MimeMultipart content))
               (map #(.getBodyPart ^MimeMultipart content %))
               (filter #(let [ct (get-content-type %)]
                          (str/includes? ct "text/html")))
               (map #(try (str (.getContent ^Part %)) (catch Exception _ "")))
               (first))
          nil))
      :else nil)
    (catch Exception _ nil)))

;; ─── Raw message -> structured email map ────────────────────────

(defn parse-raw-message
  "Parse a raw mime4j CharBufferWrapper into a structured email map
   using Jakarta Mail for header and body extraction.
   Returns a map with keys matching the Datalevin schema."
  [raw-msg]
  (let [raw-str   (str raw-msg)
        input     (ByteArrayInputStream. (.getBytes raw-str "UTF-8"))
        props     (doto (Properties.)
                    (.setProperty "mail.mime.address.strict" "false"))
        session   (Session/getDefaultInstance props)
        msg       (MimeMessage. session input)]
    {:message-id  (safe-header msg "Message-ID")
     :subject     (try (.getSubject msg) (catch Exception _ nil))
     :from        (let [from (.getFrom msg)]
                    (when (seq from) (address->str (first from))))
     :to          (addresses->seq (.getRecipients msg jakarta.mail.Message$RecipientType/TO))
     :cc          (addresses->seq (.getRecipients msg jakarta.mail.Message$RecipientType/CC))
     :date        (try (.getSentDate msg) (catch Exception _ nil))
     :body        (extract-text-body msg)
     :html        (extract-html-body msg)
     :thread-id   (or (safe-header msg "Thread-Topic")
                      (safe-header msg "References"))
     :labels      (parse-gmail-labels msg)}))

;; ─── Batch ingestion ─────────────────────────────────────────────

(defn parse-mbox
  "Parse an entire MBOX file, returning a lazy seq of structured email maps.
   Each map has keys: :message-id, :subject, :from, :to, :cc, :date,
   :body, :html, :thread-id, :labels"
  [mbox-path]
  (->> (mbox-messages mbox-path)
       (map parse-raw-message)))
