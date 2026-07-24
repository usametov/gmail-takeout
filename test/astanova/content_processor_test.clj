(ns astanova.content-processor-test
  "Unit tests for astanova.content-processor — URL extraction and text processing."
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.string :as str]
            [astanova.content-processor :as sut]))

;; ─── extract-urls ────────────────────────────────────────────────

(deftest test-extract-urls-plain
  (testing "plain URLs in text"
    (is (= ["https://example.com"]
           (sut/extract-urls "check https://example.com here")))
    (is (= ["https://youtube.com/watch?v=abc"]
           (sut/extract-urls "https://youtube.com/watch?v=abc")))))

(deftest test-extract-urls-multiple
  (testing "multiple URLs in one body"
    (is (= ["https://a.com/path" "http://b.org"]
           (sut/extract-urls "see https://a.com/path and http://b.org")))
    (is (= ["https://a.com" "https://b.com" "https://c.com"]
           (sut/extract-urls "multiple (https://a.com) and https://b.com and [https://c.com]")))))

(deftest test-extract-urls-in-delimiters
  (testing "URLs wrapped in various delimiters"
    (is (= ["https://example.com"] (sut/extract-urls "(https://example.com)")))
    (is (= ["https://example.com"] (sut/extract-urls "[https://example.com]")))
    (is (= ["https://example.com"] (sut/extract-urls "<https://example.com>")))
    (is (= ["https://example.com"] (sut/extract-urls "\"https://example.com\"")))))

(deftest test-extract-urls-trailing-punctuation
  (testing "trailing punctuation is stripped"
    (is (= ["https://example.com"] (sut/extract-urls "https://example.com.")))
    (is (= ["https://example.com"] (sut/extract-urls "https://example.com,")))
    (is (= ["https://example.com"] (sut/extract-urls "https://example.com!")))
    (is (= ["https://example.com"] (sut/extract-urls "https://example.com);")))))

(deftest test-extract-urls-nil-empty
  (testing "nil and empty input return nil/empty"
    (is (nil? (sut/extract-urls nil)))
    (is (= [] (sut/extract-urls "")))
    (is (= [] (sut/extract-urls "No URL here")))
    (is (= [] (sut/extract-urls "   ")))))

(deftest test-extract-urls-deduplication
  (testing "duplicate URLs are deduplicated"
    (is (= ["https://example.com"]
           (sut/extract-urls "https://example.com and https://example.com again")))))

(deftest test-extract-urls-youtube-variants
  (testing "YouTube URL variants"
    (is (= ["https://youtube.com/watch?v=abc"] (sut/extract-urls "https://youtube.com/watch?v=abc")))
    (is (= ["https://youtu.be/abc"] (sut/extract-urls "https://youtu.be/abc")))
    (is (= ["https://www.youtube.com/channel/UC123"] (sut/extract-urls "https://www.youtube.com/channel/UC123")))))

(deftest test-extract-urls-arxiv
  (testing "arxiv URLs"
    (is (= ["https://arxiv.org/abs/2412.20138"] (sut/extract-urls "https://arxiv.org/abs/2412.20138")))
    (is (= ["https://arxiv.org/pdf/2412.20138"] (sut/extract-urls "https://arxiv.org/pdf/2412.20138")))))

(deftest test-extract-urls-github
  (testing "GitHub URLs"
    (is (= ["https://github.com/user/repo"] (sut/extract-urls "https://github.com/user/repo")))
    (is (= ["https://github.com/user/repo"] (sut/extract-urls "check out https://github.com/user/repo for more")))))

(deftest test-extract-urls-markdown-link
  (testing "Markdown-style links"
    (is (= ["https://example.com"] (sut/extract-urls "[link](https://example.com)")))))

(deftest test-extract-urls-realistic-body
  (testing "realistic email body with mixed content"
    (let [body (str "Check out this video: https://youtube.com/watch?v=abc\n"
                    "And the paper: https://arxiv.org/abs/2412.20138\n"
                    "Code at https://github.com/user/repo")
          urls (sut/extract-urls body)]
      (is (= 3 (count urls)))
      (is (some #(str/includes? % "youtube.com") urls))
      (is (some #(str/includes? % "arxiv.org") urls))
      (is (some #(str/includes? % "github.com") urls)))))
