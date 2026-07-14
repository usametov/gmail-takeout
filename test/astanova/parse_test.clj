(ns astanova.parse-test
  "Unit tests for astanova.parse — HTML cleaning, header extraction,
   Gmail labels, and raw-message parsing via inline email fixtures."
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.string :as str]
            [astanova.parse :as sut])
  (:import [jakarta.mail Session]
           [jakarta.mail.internet MimeMessage]
           [java.io ByteArrayInputStream]
           [java.util Properties]))

;; ─── Helpers ─────────────────────────────────────────────────────

(defn- mime-msg
  "Build a Jakarta MimeMessage from a raw email string."
  [raw]
  (let [input   (ByteArrayInputStream. (.getBytes raw "UTF-8"))
        props   (doto (Properties.)
                  (.setProperty "mail.mime.address.strict" "false"))
        session (Session/getDefaultInstance props)]
    (MimeMessage. session input)))

;; ─── html->text ─────────────────────────────────────────────────

(deftest test-html->text-nil
  (testing "nil input returns nil"
    (is (nil? (sut/html->text nil)))))

(deftest test-html->text-plain
  (testing "plain text passes through unchanged"
    (is (= "Hello world" (sut/html->text "Hello world")))))

(deftest test-html->text-empty
  (testing "empty string returns empty"
    (is (= "" (sut/html->text "")))))

(deftest test-html->text-strip-tags
  (testing "simple HTML tags are stripped"
    (is (= "Hello World" (sut/html->text "<b>Hello</b> <i>World</i>")))))

(deftest test-html->text-br-to-newline
  (testing "<br> and <br/> become newlines"
    (is (= "line1\nline2" (sut/html->text "line1<br>line2")))
    (is (= "line1\nline2" (sut/html->text "line1<br/>line2")))
    (is (= "line1\nline2" (sut/html->text "line1<br />line2")))))

(deftest test-html->text-p-to-newline
  (testing "<p> and </p> become newlines (between words, not trimmed at edges)"
    (is (= "a\nb" (sut/html->text "a<p>b")))
    (is (= "a\nb" (sut/html->text "a</p>b")))
    (is (= "a\n\nb" (sut/html->text "a</p><p>b")))))

(deftest test-html->text-entities
  (testing "HTML entities are decoded between surrounding text"
    (is (= "a b" (sut/html->text "a&nbsp;b")))
    (is (= "a&b" (sut/html->text "a&amp;b")))
    (is (= "a<b" (sut/html->text "a&lt;b")))
    (is (= "a>b" (sut/html->text "a&gt;b")))
    (is (= "a\"b" (sut/html->text "a&quot;b")))))

(deftest test-html->text-numeric-entities
  (testing "numeric HTML entities are decoded"
    (is (= "A" (sut/html->text "&#65;")))
    (is (= "AB" (sut/html->text "&#65;&#66;")))
    (is (= "Hi" (sut/html->text "&#72;&#105;")))
    (is (= "a\nb" (sut/html->text "a&#10;b")))))

(deftest test-html->text-collapse-newlines
  (testing "three or more newlines collapse to two"
    (is (= "a\n\nb" (sut/html->text "a\n\n\nb")))
    (is (= "a\n\nb" (sut/html->text "a\n\n\n\nb")))))

(deftest test-html->text-trims
  (testing "output is trimmed"
    (is (= "hello" (sut/html->text "  hello  ")))
    (is (= "hello" (sut/html->text "\nhello\n")))))

(deftest test-html->text-nested
  (testing "nested HTML is fully stripped"
    (is (= "Hello World" (sut/html->text "<div><b>Hello</b> <span>World</span></div>")))))

(deftest test-html->text-mixed-content
  (testing "mixed content with tags and entities"
    (let [html "<p>Price: &pound;10 &amp; &lt; 20</p>"
          text (sut/html->text html)]
      (is (str/includes? text "Price:"))
      (is (str/includes? text "&pound;"))
      (is (str/includes? text "&"))
      (is (str/includes? text "<")))))

;; ─── safe-header ────────────────────────────────────────────────

(deftest test-safe-header-found
  (testing "returns the value when header exists"
    (let [msg (mime-msg "Subject: Test Subject\n\nBody here")]
      (is (= "Test Subject" (#'sut/safe-header msg "Subject"))))))

(deftest test-safe-header-missing
  (testing "returns nil when header does not exist"
    (let [msg (mime-msg "Subject: Hi\n\nBody")]
      (is (nil? (#'sut/safe-header msg "X-No-Such-Header"))))))

(deftest test-safe-header-multiple-values
  (testing "returns the first value when header appears multiple times"
    (let [msg (mime-msg "Received: from a\nReceived: from b\n\nBody")]
      (is (= "from a" (#'sut/safe-header msg "Received"))))))

(deftest test-safe-header-case-insensitivity
  (testing "header name is case-insensitive"
    (let [msg (mime-msg "message-id: <abc@def>\n\nBody")]
      (is (= "<abc@def>" (#'sut/safe-header msg "Message-ID")))
      (is (= "<abc@def>" (#'sut/safe-header msg "MESSAGE-ID"))))))

;; ─── parse-gmail-labels ─────────────────────────────────────────

(deftest test-parse-gmail-labels-single
  (testing "single label is returned as a one-element vector"
    (let [msg (mime-msg "X-Gmail-Labels: Inbox\n\nBody")]
      (is (= ["Inbox"] (sut/parse-gmail-labels msg))))))

(deftest test-parse-gmail-labels-multiple
  (testing "multiple comma-separated labels are split"
    (let [msg (mime-msg "X-Gmail-Labels: Inbox,Important,Opened\n\nBody")]
      (is (= ["Inbox" "Important" "Opened"] (sut/parse-gmail-labels msg))))))

(deftest test-parse-gmail-labels-whitespace
  (testing "labels with surrounding whitespace are trimmed"
    (let [msg (mime-msg "X-Gmail-Labels:  Archived ,  Opened \n\nBody")]
      (is (= ["Archived" "Opened"] (sut/parse-gmail-labels msg))))))

(deftest test-parse-gmail-labels-missing-header
  (testing "no X-Gmail-Labels header returns empty vector"
    (let [msg (mime-msg "Subject: Hello\n\nBody")]
      (is (= [] (sut/parse-gmail-labels msg))))))

(deftest test-parse-gmail-labels-multiline
  (testing "labels split across continuation lines (RFC 2822 folding)"
    (let [raw  (str "X-Gmail-Labels: Archived,Opened,Category\n"
                    " Updates,security,ai/chatbots/watson\n"
                    "\nBody")
          msg  (mime-msg raw)]
      ;; Jakarta Mail preserves the CRLF + folding whitespace
      (is (= ["Archived" "Opened" "Category Updates" "security" "ai/chatbots/watson"]
             (sut/parse-gmail-labels msg))))))

;; ─── parse-raw-message (inline fixture) ──────────────────────────

(deftest test-parse-raw-message-simple
  (testing "parses a minimal text email"
    (let [raw  (str "From: alice@example.com\n"
                    "To: bob@example.com\n"
                    "Subject: Hello\n"
                    "Message-ID: <abc@def>\n"
                    "Date: Mon, 1 Jan 2024 10:00:00 +0000\n"
                    "\n"
                    "Hi Bob, how are you?")
          parsed (sut/parse-raw-message raw)]
      (is (= "<abc@def>" (:message-id parsed)))
      (is (= "Hello" (:subject parsed)))
      (is (= "alice@example.com" (:from parsed)))
      (is (= ["bob@example.com"] (:to parsed)))
      (is (instance? java.util.Date (:date parsed)))
      (is (str/includes? (:body parsed) "Hi Bob"))
      (is (= [] (:labels parsed))))))

(deftest test-parse-raw-message-multipart
  (testing "parses a multipart/alternative email (text+html)"
    (let [raw  (str "From: bob@example.com\n"
                    "To: alice@example.com\n"
                    "Subject: Multipart test\n"
                    "Message-ID: <multi@test>\n"
                    "MIME-Version: 1.0\n"
                    "Content-Type: multipart/alternative; boundary=outer\n"
                    "\n"
                    "--outer\n"
                    "Content-Type: text/plain; charset=utf-8\n"
                    "\n"
                    "Plain text body\n"
                    "--outer\n"
                    "Content-Type: text/html; charset=utf-8\n"
                    "\n"
                    "<b>HTML</b> body\n"
                    "--outer--\n")
          parsed (sut/parse-raw-message raw)]
      (is (= "Multipart test" (:subject parsed)))
      (is (str/includes? (:body parsed) "Plain text body")
          "plain text body is preferred")
      (is (some? (:html parsed))
          "HTML body is also captured")
      (is (str/includes? (:html parsed) "HTML")))))

(deftest test-parse-raw-message-with-labels
  (testing "parses Gmail labels from X-Gmail-Labels header"
    (let [raw  (str "From: c@d.com\n"
                    "Subject: Labeled\n"
                    "Message-ID: <labels@test>\n"
                    "X-Gmail-Labels: Inbox,Important,ai/chatbots/watson\n"
                    "\n"
                    "Body content")
          parsed (sut/parse-raw-message raw)]
      (is (= ["Inbox" "Important" "ai/chatbots/watson"] (:labels parsed))))))

(deftest test-parse-raw-message-cc
  (testing "CC recipients are captured"
    (let [raw  (str "From: a@b.com\n"
                    "To: b@c.com\n"
                    "Cc: c@d.com, d@e.com\n"
                    "Subject: CC test\n"
                    "Message-ID: <cc@test>\n"
                    "\n"
                    "Body")
          parsed (sut/parse-raw-message raw)]
      (is (= ["c@d.com" "d@e.com"] (:cc parsed))))))

(deftest test-parse-raw-message-leading-newline
  (testing "leading whitespace in raw msg does not break header parsing"
    (let [raw  (str "\nFrom: a@b.com\nSubject: Leading NL\nMessage-ID: <nl@test>\n\nBody")
          parsed (sut/parse-raw-message raw)]
      (is (= "Leading NL" (:subject parsed)))
      (is (= "a@b.com" (:from parsed))))))

(deftest test-parse-raw-message-thread-id
  (testing "Thread-Topic is used as thread-id"
    (let [raw  (str "From: a@b.com\n"
                    "Subject: Thread\n"
                    "Message-ID: <thread@test>\n"
                    "Thread-Topic: Original Conversation\n"
                    "\n"
                    "Body")
          parsed (sut/parse-raw-message raw)]
      (is (= "Original Conversation" (:thread-id parsed))))))

(deftest test-parse-raw-message-references-as-thread
  (testing "References header serves as fallback thread-id"
    (let [raw  (str "From: a@b.com\n"
                    "Subject: Reply\n"
                    "Message-ID: <reply@test>\n"
                    "References: <orig@test>\n"
                    "\n"
                    "Body")
          parsed (sut/parse-raw-message raw)]
      (is (= "<orig@test>" (:thread-id parsed))))))