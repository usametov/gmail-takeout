(ns astanova.entity.linkedin
  "Parse LinkedIn job-alert emails into job-posting entities.

   LinkedIn emails contain several job cards separated by long dashes.  The
   parser is pure: it returns entity maps with evidence and never opens the DB."
  (:require [astanova.entity :as entity]
            [clojure.string :as str])
  (:import [java.time Instant LocalDateTime ZoneOffset]
           [java.util Date]))

(def ^:private separator-pattern #"(?m)^[ \t]*-{10,}[ \t]*$")

(def ^:private positive-noise
  #{"Jobs that match your profile"
    "Based on your title and location. Update:"
    "Apply with resume & profile"
    "Easy Apply"
    "Be an early applicant"
    "View job"})

(defn get* [m & ks]
  (some (fn [k] (let [v (get m k)] (when (some? v) v))) ks))

(defn html-unescape [s]
  (-> (str s)
      (str/replace "&amp;" "&")
      (str/replace "&#39;" "'")
      (str/replace "&apos;" "'")
      (str/replace "&quot;" "\"")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")))

(defn email-body [email]
  (some-> (get* email :body_truncated :body-truncated :body :email/body-truncated
                "body_truncated" "body")
          str
          html-unescape))

(defn email-id [email]
  (some-> (get* email :messageId :message-id :email/id "messageId" "message-id")
          str))

(defn email-from [email]
  (some-> (get* email :from :email/from "from") str))

(defn email-date [email]
  (let [raw (get* email :internalDate :internal-date :email/date "internalDate" "date")
        iso (get* email :internalDateIso :internal-date-iso "internalDateIso")]
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

(defn linkedin-email? [email]
  (let [from (str/lower-case (or (email-from email) ""))]
    (or (str/includes? from "linkedin.com")
        (str/includes? from "jobs-noreply@linkedin"))))

(defn- job-url [block]
  (some->> block
           (re-find #"https?://[^\s]*linkedin\.com/(?:comm/)?jobs/view/\d+[^\s]*")
           entity/canonical-linkedin-job-url))

(defn- noise-line? [line]
  (let [l (str/trim line)
        ll (str/lower-case l)]
    (or (str/blank? l)
        (contains? positive-noise l)
        (str/starts-with? ll "jobs that match")
        (str/starts-with? ll "based on your")
        (str/starts-with? ll "update:")
        (str/starts-with? ll "view job")
        (str/starts-with? ll "apply with")
        (str/starts-with? ll "easy apply")
        (str/starts-with? l "http://")
        (str/starts-with? l "https://")
        (str/includes? ll "linkedin.com/comm/jobs")
        (str/includes? ll "linkedin.com/jobs/view")
        (re-matches #"-{10,}" l))))

(defn parse-block
  "Parse one LinkedIn card block.  Returns a raw job map or nil."
  [block]
  (when-let [url (job-url block)]
    (let [lines (->> (str/split-lines block)
                     (map str/trim)
                     (remove noise-line?)
                     vec)
          [title company location] (take 3 lines)]
      (when (and title (not (str/blank? title)))
        {:title       title
         :company     (or (entity/clean company) "")
         :location    (or (entity/clean location) "")
         :url         url
         :source-id   (entity/linkedin-job-source-id url)
         :block       (str/trim block)}))))

(defn- entity-for-job
  [job email]
  (let [email-id (email-id email)
        posted   (email-date email)
        eid      (entity/job-id {:company   (:company job)
                                 :title     (:title job)
                                 :location  (:location job)
                                 :posted-at posted})
        eid-ref  [:entity/id eid]
        evidence (cond-> {:evidence/id (entity/evidence-id
                                        eid email-id (:url job) :linkedin-email)
                          :evidence/entity eid-ref
                          :evidence/parser :linkedin-email
                          :evidence/snippet (:block job)}
                   email-id (assoc :evidence/email-id email-id)
                   (:url job) (assoc :evidence/url (:url job))
                   posted (assoc :evidence/observed-at posted))]
    {:entity/id       eid
     :entity/class    :job-posting
     :entity/title    (:title job)
     :entity/source   :linkedin
     :entity/status   :active
     :entity/confidence 0.8
     :job/title       (:title job)
     :job/company-raw (:company job)
     :job/role-raw    (:title job)
     :job/location-raw (if (seq (:location job)) [(:location job)] [])
     :job/source-id   (:source-id job)
     :job/url         [(:url job)]
     :job/posted-at   posted
     :job/dedup-key   (entity/job-dedup-key {:company   (:company job)
                                             :title     (:title job)
                                             :location  (:location job)
                                             :posted-at posted})
     :entity/evidence [evidence]}))

(defn- merge-entity [a b]
  (assoc a
         :entity/title (or (:entity/title a) (:entity/title b))
         :job/url (vec (sort (distinct (concat (:job/url a []) (:job/url b [])))))
         :job/location-raw (vec (distinct (concat (:job/location-raw a [])
                                                  (:job/location-raw b []))))
         :entity/evidence (vec (vals (into {}
                                            (map (fn [e] [(:evidence/id e) e])
                                                 (concat (:entity/evidence a [])
                                                         (:entity/evidence b []))))))))

(defn parse-linkedin-emails
  "Parse a sequence of email maps into deduplicated job entities.

   Returns {:entities [...] :stats {...}}."
  [emails]
  (let [emails (filter linkedin-email? emails)
        collected
        (reduce
         (fn [acc email]
           (let [body (email-body email)
                 blocks (if (str/blank? (str body))
                          []
                          (str/split body separator-pattern))
                 parsed (keep parse-block blocks)]
             (-> acc
                 (update :emails inc)
                 (update :blocks + (count blocks))
                 (update :parsed + (count parsed))
                 (update :skipped + (- (count blocks) (count parsed)))
                 (update :entities into (map #(entity-for-job % email) parsed)))))
         {:emails 0 :blocks 0 :parsed 0 :skipped 0 :entities []}
         emails)
        merged (vals (reduce (fn [m e]
                               (update m (:entity/id e)
                                       (fn [old] (if old (merge-entity old e) e))))
                             {}
                             (:entities collected)))]
    {:entities (vec (sort-by :entity/id merged))
     :stats (assoc (dissoc collected :entities) :jobs (count merged))}))
