(ns astanova.content-processor
  "Content processing utilities: URL extraction, text analysis, etc."
  (:require [clojure.string :as str]))

;; ─── URL Extraction ──────────────────────────────────────────────

(def ^:private url-pattern
  "Matches https?:// URLs. Stops at whitespace, angle brackets, double quotes,
   or certain punctuation. Uses a capture group so re-seq returns vectors,
   making extraction unambiguous."
  #"(https?://[^\s<>\"()\[\]']+)")

(defn- clean-url
  "Strip trailing punctuation that likely isn't part of the URL."
  [url]
  (str/replace url #"[.,;:!?)]+$" ""))

(defn extract-urls
  "Extract unique https?:// URLs from text.
   Returns a vector of URL strings, or empty vector for nil/blank input.

   Example:
     (extract-urls \"Check https://example.com and http://test.org/path\")
     ;; => [\"https://example.com\" \"http://test.org/path\"]"
  [text]
  (when text
    (->> (re-seq url-pattern text)
         (map second)                             ;; second = the capture group
         (map clean-url)
         (remove #(str/ends-with? % "."))         ;; trailing dot in sentence end
         distinct
         vec)))

