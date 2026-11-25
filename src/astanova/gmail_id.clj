(ns astanova.gmail-id
  "Canonical Gmail message/thread identifiers.

   Gmail API IDs are hexadecimal 64-bit unsigned integers; Takeout's From_ /
   X-GM-THRID values are the same numbers in decimal.  The database stores the
   canonical decimal form, and conversion happens only at the gws boundary."
  (:require [clojure.string :as str]))

(def ^:private max-unsigned-64
  (BigInteger. "18446744073709551615"))

(def ^:private hex-pattern #"(?i)^[0-9a-f]{1,16}$")
(def ^:private decimal-pattern #"^[0-9]{1,20}$")

(defn- parse-positive [s radix]
  (when (string? s)
    (let [s (str/trim s)]
      (when (and (re-matches (if (= radix 16) hex-pattern decimal-pattern) s)
                 (or (= radix 16)
                     (= s "0")
                     (not (str/starts-with? s "0"))))
        (try
          (let [n (BigInteger. s radix)]
            (when (and (pos? (.signum n))
                       (<= (.compareTo n max-unsigned-64) 0))
              n))
          (catch Exception _ nil))))))

(defn canonical?
  "True when s is the canonical decimal form of a Gmail 64-bit ID."
  [s]
  (boolean (parse-positive s 10)))

(defn api-hex?
  "True when s looks like a Gmail API hexadecimal ID."
  [s]
  (boolean (parse-positive s 16)))

(defn api-hex->canonical
  "Convert a Gmail API hex ID to the canonical decimal string, or nil."
  [s]
  (when-let [n (parse-positive s 16)]
    (str n)))

(defn canonical->api-hex
  "Convert a canonical decimal Gmail ID back to the API hex form, or nil."
  [s]
  (when-let [n (parse-positive s 10)]
    (.toString n 16)))

(defn synthetic?
  "True for dry-run/fake IDs that must never be written to the database."
  [s]
  (let [s (str/lower-case (str s))]
    (boolean
     (or (str/starts-with? s "gmail-")
         (str/starts-with? s "thread-")
         (str/starts-with? s "test-")
         (str/starts-with? s "fake-")
         (str/includes? s "synthetic")))))

(defn canonicalize
  "Normalize an ID according to its source.

   :canonical - already decimal
   :gws-api   - Gmail API hexadecimal
   nil        - accept canonical decimal first, then API hex

   Returns nil for invalid or synthetic values."
  ([s] (canonicalize s nil))
  ([s encoding]
   (when-not (synthetic? s)
     (case encoding
       :canonical (when (canonical? s) (str/trim s))
       :gws-api   (api-hex->canonical s)
       nil        (or (when (canonical? s) (str/trim s))
                      (api-hex->canonical s))))))

(defn message-iri [id]
  (when-let [c (canonicalize id)]
    (str "gmail:message/" c)))

(defn thread-iri [id]
  (when-let [c (canonicalize id)]
    (str "gmail:thread/" c)))
