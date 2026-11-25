(ns astanova.entity.store
  "Datalevin persistence for generated entities and evidence.

   Pure entity IDs/validation live in astanova.entity; this namespace owns
   class seeding, ref resolution, and upserts."
  (:require [astanova.entity :as entity]
            [astanova.ontology.graph :as graph]
            [clojure.string :as str]
            [datalevin.core :as d])
  (:import [java.util Date]))

(def ^:private default-batch-size 100)

(defn ensure-classes!
  "Seed term and entity classes.  Idempotent."
  ([conn] (ensure-classes! conn graph/default-schema-path))
  ([conn schema-path]
   (graph/seed-classes! conn (graph/load-schema schema-path))
   conn))

(defn- one [db query & args]
  (apply d/q query db args))

(defn- class-eid [db class-id]
  (when class-id
    (one db '[:find ?e . :in $ ?id :where [?e :class/id ?id]] class-id)))

(defn- term-eid [db term-id]
  (when term-id
    (one db '[:find ?e . :in $ ?id :where [?e :term/id ?id]] term-id)))

(defn- entity-eid [db id]
  (when id
    (one db '[:find ?e . :in $ ?id :where [?e :entity/id ?id]] id)))

(defn- email-eid [db id]
  (when id
    (one db '[:find ?e . :in $ ?id :where [?e :email/id ?id]] id)))

(defn- resolve-term-ref [db v]
  (cond
    (nil? v) nil
    (map? v) (or (term-eid db (:term/id v))
                 (when (number? (:db/id v)) (:db/id v))
                 (when (number? (:term/id v)) (:term/id v)))
    :else v))

(defn- resolve-entity-ref [db v]
  (cond
    (nil? v) nil
    (map? v) (or (entity-eid db (:entity/id v))
                 (when (number? (:db/id v)) (:db/id v))
                 (when (number? (:entity/id v)) (:entity/id v)))
    :else v))

(defn- as-refs [v]
  (cond
    (nil? v) []
    (map? v) [v]
    (coll? v) v
    :else [v]))

(defn- resolve-refs [db v]
  (let [rs (keep #(resolve-term-ref db %) (as-refs v))]
    (if (or (set? v) (vector? v) (sequential? v))
      (vec rs)
      (first rs))))

(defn- resolve-entity-refs [db v]
  (let [rs (keep #(resolve-entity-ref db %) (as-refs v))]
    (if (or (set? v) (vector? v) (sequential? v))
      (vec rs)
      (first rs))))

(defn- remove-nils [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

(defn- prepared-entity
  [db e now]
  (let [class (class-eid db (:entity/class e))]
    (when-not class
      (throw (ex-info (str "Unknown entity class: " (:entity/class e))
                      {:entity-id (:entity/id e)
                       :class (:entity/class e)})))
    (-> e
        (dissoc :entity/evidence)
        (assoc :entity/class class
               :entity/created-at (or (:entity/created-at e) now)
               :entity/updated-at now)
        (cond-> (contains? e :job/company)
          (update :job/company #(resolve-term-ref db %)))
        (cond-> (contains? e :job/role)
          (update :job/role #(resolve-term-ref db %)))
        (cond-> (contains? e :job/location)
          (update :job/location #(resolve-refs db %)))
        (cond-> (contains? e :job/field)
          (update :job/field #(resolve-refs db %)))
        (cond-> (contains? e :job/job-type)
          (update :job/job-type #(resolve-refs db %)))
        (cond-> (contains? e :channel/topic)
          (update :channel/topic #(resolve-term-ref db %)))
        (cond-> (contains? e :program/field)
          (update :program/field #(resolve-refs db %)))
        (cond-> (contains? e :program/channel)
          (update :program/channel #(resolve-entity-ref db %)))
        (cond-> (contains? e :program/episode)
          (update :program/episode #(resolve-entity-refs db %)))
        (cond-> (contains? e :episode/program)
          (update :episode/program #(resolve-entity-refs db %)))
        remove-nils)))

(defn- prepared-evidence
  [db entity-key entity-eid ev now]
  (let [eid (or (:evidence/id ev)
                (entity/evidence-id entity-key
                                    (:evidence/email-id ev)
                                    (:evidence/url ev)
                                    (:evidence/parser ev)))]
    (when-not eid
      (throw (ex-info "Evidence needs an id or enough fields to derive one"
                      {:evidence ev})))
    (-> ev
        (dissoc :evidence/entity)
        (assoc :evidence/id eid
               :evidence/entity entity-eid
               :evidence/observed-at (or (:evidence/observed-at ev) now))
        (cond-> (:evidence/email-id ev)
          (assoc :evidence/email (email-eid db (:evidence/email-id ev))))
        remove-nils)))

(defn- merge-many [old new]
  (vec (distinct (concat (as-refs old) (as-refs new)))))

(defn- prepare-existing [db e]
  (when-let [id (:entity/id e)]
    (some-> (d/entity db [:entity/id id]))))

(defn- merge-existing [old new]
  (cond-> new
    old (assoc :entity/created-at (or (:entity/created-at old)
                                      (:entity/created-at new)))
    (and old (:job/url old)) (assoc :job/url (merge-many (:job/url old) (:job/url new)))
    (and old (:job/location-raw old)) (assoc :job/location-raw
                                             (merge-many (:job/location-raw old)
                                                         (:job/location-raw new)))
    (and old (str/blank? (str (:entity/title new)))) (assoc :entity/title (:entity/title old))))

(defn upsert-entities!
  "Upsert entity maps by :entity/id and evidence by :evidence/id.

   Options: :dry-run, :batch-size, :schema-path.
   Returns {:entities n :inserted n :updated n :evidence n :dry-run bool}."
  [conn entities & {:keys [dry-run batch-size schema-path]
                    :or   {batch-size default-batch-size
                           schema-path graph/default-schema-path}}]
  (let [entities (vec entities)
        validation (entity/validate-entities entities {})]
    (when (seq (:errors validation))
      (throw (ex-info "Entity validation failed"
                      {:errors (:errors validation)
                       :warnings (:warnings validation)})))
    (if dry-run
      (let [db (d/db conn)
            existing (count (filter #(entity-eid db (:entity/id %)) entities))]
        {:entities (count entities)
         :inserted (- (count entities) existing)
         :updated existing
         :evidence (reduce + 0 (map #(count (:entity/evidence %)) entities))
         :dry-run true
         :warnings (:warnings validation)})
      (do
        (ensure-classes! conn schema-path)
        (let [db (d/db conn)
              now (Date.)
              prepared (mapv #(prepared-entity db % now) entities)
              existing (into {} (keep (fn [e]
                                        (when-let [old (prepare-existing db e)]
                                          [(:entity/id e) old]))
                                      prepared))
              new-count (count (remove #(contains? existing (:entity/id %)) prepared))
              update-count (count (filter #(contains? existing (:entity/id %)) prepared))
              tx-entities (mapv #(merge-existing (get existing (:entity/id %)) %) prepared)
              evidence-groups (mapv (fn [e]
                                      [(:entity/id e) (:entity/evidence e)])
                                    entities)]
          (doseq [batch (partition-all batch-size tx-entities)]
            (d/transact! conn batch))
          (let [evidence-count (atom 0)]
            (doseq [[eid evs] evidence-groups
                    :when (seq evs)]
              (let [entity-eid (entity-eid (d/db conn) eid)
                    prepared-evs (mapv #(prepared-evidence (d/db conn) eid entity-eid % now)
                                       evs)
                    _ (doseq [batch (partition-all batch-size prepared-evs)]
                        (d/transact! conn batch))]
                (swap! evidence-count + (count prepared-evs))))
            {:entities (count entities)
             :inserted new-count
             :updated update-count
             :evidence @evidence-count
             :dry-run false
             :warnings (:warnings validation)}))))))
