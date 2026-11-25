(ns astanova.entity
  "Entity model helpers: deterministic IDs, dedup keys, and validation.

   Entities are ABox individuals (jobs, TV channels/programs/episodes), not
   ontology terms.  This namespace is pure and does not open Datalevin."
  (:require [astanova.ontology.rules :as rules]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant YearMonth ZoneOffset]
           [java.util Date]))

(def entity-classes
  #{:job-posting :tv-channel :tv-program :tv-episode})

(def job-category-fields
  {:job/company   "companies"
   :job/role      "roles"
   :job/location  "locations"
   :job/job-type  "job_types"
   :job/field     "fields"})

(defn sha256 [s]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str s) "UTF-8"))]
    (format "%064x" (BigInteger. 1 bytes))))

(defn clean [s]
  (some-> s str str/trim (str/replace #"\s+" " ") not-empty))

(defn normalization-key [s]
  (some-> s clean rules/normalization-key))

(defn- instant->month [^Instant inst]
  (str (YearMonth/from (.atZone inst ZoneOffset/UTC))))

(defn month-token
  "Return YYYY-MM for a Date/Instant/epoch-ms/ISO string, or \"unknown\"."
  [d]
  (cond
    (nil? d) "unknown"
    (instance? Date d) (instant->month (.toInstant ^Date d))
    (instance? Instant d) (instant->month d)
    (number? d) (instant->month (Instant/ofEpochMilli (long d)))
    (string? d) (or (try (instant->month (Instant/parse d))
                         (catch Exception _ nil))
                    (when (re-matches #"\d{4}-\d{2}.*" (str/trim d))
                      (subs (str/trim d) 0 7))
                    "unknown")
    :else "unknown"))

(defn job-dedup-key
  "Stable key for a posting.  Includes the posting month to avoid merging
   reposted roles across years."
  [{:keys [company title location posted-at]}]
  (str (or (normalization-key company) "")
       "|" (or (normalization-key title) "")
       "|" (or (normalization-key (if (coll? location) (first location) location)) "")
       "|" (month-token posted-at)))

(defn job-id [job]
  (str "job:" (sha256 (job-dedup-key job))))

(defn program-id [email-id]
  (when-let [id (clean email-id)]
    (str "program:" id)))

(defn video-id? [s]
  (boolean (and (string? s) (re-matches #"[A-Za-z0-9_-]{11}" s))))

(defn episode-id [video-id]
  (when (video-id? video-id)
    (str "episode:" video-id)))

(defn channel-id [slug]
  (str "channel:" (or (clean slug) "unknown")))

(defn evidence-id [entity-id email-id url parser]
  (str "evidence:" (sha256 (str entity-id "|" (or email-id "") "|"
                                (or url "") "|" (name (or parser :unknown))))))

(defn linkedin-job-source-id [url]
  (some->> url
           (re-find #"(?:https?://)?(?:www\.)?linkedin\.com/(?:comm/)?jobs/view/(\d+)")
           second))

(defn canonical-linkedin-job-url [url]
  (when-let [id (linkedin-job-source-id url)]
    (str "https://www.linkedin.com/jobs/view/" id "/")))

(defn entity-class? [c]
  (contains? entity-classes c))

(defn- ref-id [ref]
  (cond
    (map? ref) (or (:term/id ref) (:entity/id ref) (:class/id ref))
    :else ref))

(defn- term-category [term-lookup ref]
  (let [id (ref-id ref)]
    (cond
      (and (map? ref) (:term/category ref)) (:term/category ref)
      (and term-lookup id) (get term-lookup id)
      :else nil)))

(defn validate-entity
  "Validate one entity map.  `term-lookup` maps term ids -> category strings
   for category checks; refs may also carry :term/category directly.

   Returns {:errors [...] :warnings [...]}."
  ([entity] (validate-entity entity {}))
  ([entity term-lookup]
   (let [errors (atom [])
         warnings (atom [])
         class (:entity/class entity)
         eid (:entity/id entity)]
     (when (or (nil? eid) (str/blank? (str eid)))
       (swap! errors conj {:type :missing-entity-id :entity entity}))
     (when-not (entity-class? class)
       (swap! errors conj {:type :invalid-entity-class
                           :entity eid :class class}))
     (when (str/blank? (str (:entity/title entity)))
       (swap! warnings conj {:type :missing-title :entity eid}))
     (when (= class :job-posting)
       (when (str/blank? (str (:job/title entity)))
         (swap! warnings conj {:type :missing-job-title :entity eid}))
       (when (str/blank? (str (:job/company-raw entity)))
         (swap! warnings conj {:type :missing-job-company :entity eid}))
       (doseq [[attr expected] job-category-fields
               ref (let [v (get entity attr)]
                     (cond
                       (nil? v) []
                       (map? v) [v]
                       (coll? v) v
                       :else [v]))
               :let [actual (term-category term-lookup ref)]
               :when (and actual (not= (name actual) expected))]
         (swap! errors conj {:type :term-category-mismatch
                             :entity eid
                             :attribute attr
                             :expected expected
                             :actual actual
                             :term (ref-id ref)})))
     (when (= class :tv-episode)
       (when-not (video-id? (:episode/video-id entity))
         (swap! errors conj {:type :invalid-video-id
                             :entity eid
                             :video-id (:episode/video-id entity)})))
     (when (= class :tv-program)
       (when (str/blank? (str (:program/channel entity)))
         (swap! errors conj {:type :missing-program-channel :entity eid})))
     (let [evidence (:entity/evidence entity)
           ids (keep :evidence/id evidence)]
       (when (and (seq evidence) (not= (count ids) (count (set ids))))
         (swap! errors conj {:type :duplicate-evidence-id :entity eid})))
     {:errors @errors :warnings @warnings})))

(defn validate-entities
  [entities term-lookup]
  (reduce (fn [acc e]
            (let [v (validate-entity e term-lookup)]
              (-> acc
                  (update :errors into (:errors v))
                  (update :warnings into (:warnings v)))))
          {:errors [] :warnings []}
          entities))
