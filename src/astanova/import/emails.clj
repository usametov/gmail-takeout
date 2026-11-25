(ns astanova.import.emails
  "Adapt Antigravity JSON/EDN email exports to the internal email map.

   These files are arrays of enriched email records, not entity arrays.  The
   adapter canonicalizes Gmail IDs, converts dates, and separates HTML from
   plain text before ingest/upsert."
  (:require [astanova.gmail-id :as gid]
            [astanova.parse :as parse]
            [clojure.string :as str])
  (:import [java.time Instant LocalDateTime ZoneOffset]
           [java.util Date]))

(defn get* [m & ks]
  (some (fn [k] (let [v (get m k)] (when (some? v) v))) ks))

(defn- html? [s]
  (boolean (and (string? s)
                (re-find #"(?i)<(?:!doctype|html|head|body|div|p|br|table|span)\b" s))))

(defn- strip-tags [s]
  (-> (str s)
      (str/replace #"(?is)<script.*?</script>" " ")
      (str/replace #"(?is)<style.*?</style>" " ")
      (str/replace #"<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- body-fields [m]
  (let [raw (some-> (get* m :body_truncated :body-truncated :email/body-truncated
                          "body_truncated")
                    str)]
    (cond
      (str/blank? raw) {:body ""}
      (html? raw) {:html raw
                   :body (let [text (try (parse/html->text raw)
                                         (catch Exception _ nil))]
                           (if (str/blank? (str text)) (strip-tags raw) text))}
      :else {:body raw})))

(defn- as-list [x]
  (cond
    (nil? x) nil
    (sequential? x) (vec (remove str/blank? (map str x)))
    (string? x) (let [s (str/trim x)]
                  (when (seq s) [s]))
    :else [(str x)]))

(defn- parse-date [m]
  (let [raw (get* m :internalDate :internal-date :email/date "internalDate" "date")
        iso (get* m :internalDateIso :internal-date-iso "internalDateIso")]
    (cond
      (instance? Date raw) raw
      (instance? Instant raw) (Date/from ^Instant raw)
      (number? raw) (Date. (long raw))
      (and (string? raw) (re-matches #"\d+" (str/trim raw)))
      (Date. (Long/parseLong (str/trim raw)))
      (and (string? raw) (seq (str/trim raw)))
      (try (Date/from (Instant/parse (str/trim raw)))
           (catch Exception _
             (try (Date/from (.toInstant (LocalDateTime/parse (str/trim raw))
                                         ZoneOffset/UTC))
                  (catch Exception _ nil))))
      (and (string? iso) (seq (str/trim iso)))
      (try (Date/from (.toInstant (LocalDateTime/parse (str/trim iso)) ZoneOffset/UTC))
           (catch Exception _ nil))
      :else nil)))

(defn- canonical-id [v]
  (when-not (str/blank? (str v))
    (or (gid/canonicalize v :canonical)
        (gid/canonicalize v :gws-api))))

(defn- body-links [m]
  (let [extract (or (:body_extract m) (get m "body_extract"))
        links   (or (:links extract) (get extract "links"))]
    (when (seq links)
      (vec (distinct (keep #(when-not (str/blank? (str %)) (str %)) links))))))

(defn normalize-email
  "Convert one Antigravity email record to the internal email map.
   Returns nil when the Message-ID is missing."
  [m source]
  (let [message-id (some-> (get* m :messageId :message-id :email/id "messageId" "message-id")
                           str str/trim)]
    (when (seq message-id)
      (let [body (body-fields m)]
        (cond-> {:message-id message-id
                 :gmail-id   (canonical-id (get* m :gmailId :gmail-id "gmailId"))
                 :thread-id  (canonical-id (get* m :threadId :thread-id "threadId"))
                 :subject    (some-> (get* m :subject "subject") str)
                 :from       (some-> (get* m :from :email/from "from") str)
                 :to         (as-list (get* m :to :email/to "to"))
                 :cc         (as-list (get* m :cc :email/cc "cc"))
                 :date       (parse-date m)
                 :labels     (as-list (get* m :labels "labels"))
                 :links      (body-links m)
                 :source     source}
          (seq (:body body)) (assoc :body (:body body))
          (seq (:html body)) (assoc :html (:html body)))))))

(defn normalize-emails
  "Normalize a sequence of Antigravity email records.  Returns
   {:emails [...] :total n :skipped n}."
  [data source]
  (let [items (cond
                (vector? data) data
                (sequential? data) (vec data)
                (and (map? data) (vector? (:emails data))) (:emails data)
                (and (map? data) (vector? (:results data))) (:results data)
                :else [])
        emails (vec (keep #(normalize-email % source) items))]
    {:emails emails
     :total (count items)
     :skipped (- (count items) (count emails))}))

(defn email-source-name [path]
  (some-> path java.io.File. .getName))
