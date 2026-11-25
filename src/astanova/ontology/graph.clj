(ns astanova.ontology.graph
  "Ontology graph schema, EDN rule loading, and validation.

   The graph is a typed property graph in Datalevin.  It is RDF-like but not a
   generic triple store; see doc/ontology-reasoning.md."
  (:require [astanova.ontology.rules :as rules]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datalevin.core :as d]))

(def schema
  "Datalevin attributes for the ontology graph."
  {:class/id           {:db/valueType   :db.type/keyword
                        :db/unique      :db.unique/identity
                        :db/doc         "Stable class keyword"}
   :class/label        {:db/valueType   :db.type/string}
   :class/parent       {:db/valueType   :db.type/ref
                        :db/cardinality :db.cardinality/many}
   :class/disjoint-with {:db/valueType  :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :class/status       {:db/valueType   :db.type/keyword}

   :term/id            {:db/valueType   :db.type/string
                        :db/unique      :db.unique/identity}
   :term/normalized     {:db/valueType   :db.type/string}
   :term/category       {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/one}
   :term/same-as        {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :term/broader        {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :term/narrower       {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :term/related        {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :term/status         {:db/valueType   :db.type/keyword}
   :term/source         {:db/valueType   :db.type/keyword}
   :term/confidence     {:db/valueType   :db.type/double}

   :form/raw            {:db/valueType   :db.type/string
                         :db/unique      :db.unique/identity}
   :form/term           {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/one}

   :label/name          {:db/valueType   :db.type/string
                         :db/unique      :db.unique/identity}
   :label/parent        {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :label/category      {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/one}
   :label/source        {:db/valueType   :db.type/keyword}})

(def default-schema-path "ontology/schema.edn")
(def default-rules-path "ontology/rules.edn")

(defn create-conn
  "Open a Datalevin connection with the ontology graph schema.

   Datalevin expects the schema map as the second argument, not wrapped in
   {:schema ...}."
  [path]
  (d/get-conn path schema))

(defn read-edn [path]
  (edn/read-string (slurp path)))

(defn class-cycle?
  "Return a cycle path when the class hierarchy has a cycle."
  [classes]
  (let [state (atom {})
        path  (atom [])]
    (letfn [(visit [c]
              (case (get @state c)
                :done nil
                :visiting (conj @path c)
                (do
                  (swap! state assoc c :visiting)
                  (swap! path conj c)
                  (let [found (when-let [parents (:parents (get classes c))]
                                (some visit parents))]
                    (swap! path pop)
                    (if found
                      found
                      (do (swap! state assoc c :done) nil))))))]
      (some visit (keys classes)))))

(defn all-classes
  "Merge term-category classes and entity classes."
  [schema-data]
  (merge (:classes schema-data) (:entity-classes schema-data)))

(defn all-disjoint
  "Merge term-category and entity disjointness pairs."
  [schema-data]
  (concat (:disjoint schema-data) (:entity-disjoint schema-data)))

(defn load-schema
  "Read and validate ontology/schema.edn."
  [path]
  (let [data (read-edn path)]
    (when-not (map? data)
      (throw (ex-info "Ontology schema must be an EDN map" {:path path})))
    (let [classes (all-classes data)]
      (when-not (and (map? (:classes data)) (seq (:classes data)))
        (throw (ex-info "Ontology schema must define a non-empty :classes map"
                        {:path path})))
      (doseq [[id {:keys [parents]}] classes
              p parents]
        (when-not (contains? classes p)
          (throw (ex-info (str "Class " id " has unknown parent " p)
                          {:class id :parent p}))))
      (when-let [cycle (class-cycle? classes)]
        (throw (ex-info (str "Class hierarchy contains a cycle: " (str/join " -> " cycle))
                        {:cycle cycle})))
      (doseq [[a b] (all-disjoint data)]
        (when-not (and (contains? classes a) (contains? classes b))
          (throw (ex-info (str "Disjoint pair references unknown class: " a ", " b)
                          {:a a :b b}))))
      data)))

(defn load-rule-data
  "Read ontology/rules.edn and validate its shape."
  [path]
  (let [data (read-edn path)
        rs   (:rules data)
        vs   (:validation data)]
    (when-not (and (vector? rs) (every? vector? rs))
      (throw (ex-info "Ontology rules must be a vector of Datalog rule vectors"
                      {:path path})))
    (when-not (or (nil? vs) (map? vs))
      (throw (ex-info "Ontology validation rules must be a map"
                      {:path path})))
    data))

(defn load-rules
  "Read ontology/rules.edn and return its :rules vector."
  [path]
  (:rules (load-rule-data path)))

(defn load-validation
  "Read ontology/rules.edn and return its named validation queries."
  [path]
  (or (:validation (load-rule-data path)) {}))

(defn seed-classes!
  "Insert schema classes, parent edges, and symmetric disjointness edges."
  [conn schema-data]
  (let [classes (all-classes schema-data)
        base    (mapv (fn [[id {:keys [label status]}]]
                        (cond-> {:class/id id
                                 :class/status (or status :active)}
                          label (assoc :class/label label)))
                      classes)]
    (d/transact! conn base)
    (let [ids (into {} (d/q '[:find ?id ?e :where [?e :class/id ?id]]
                            (d/db conn)))]
      (doseq [[id {:keys [parents]}] classes
              p parents]
        (d/transact! conn [{:db/id (get ids id)
                            :class/parent (get ids p)}]))
      (doseq [[a b] (all-disjoint schema-data)]
        (d/transact! conn [{:db/id (get ids a)
                            :class/disjoint-with (get ids b)}
                           {:db/id (get ids b)
                            :class/disjoint-with (get ids a)}]))
      conn)))

(defn graph-schema []
  schema)

(defn- select-errors [db query label]
  (mapv (fn [row]
          {:type label :data (vec row)})
        (d/q query db)))

(defn validate-graph
  "Validate structural and ontology invariants over a Datalevin graph.

   Returns {:errors [...] :warnings [...]}; an empty :errors means valid."
  [conn]
  (let [db (d/db conn)
        errors
        (into []
              (concat
               (select-errors
                db
                '[:find ?t :where [?t :term/id _] (not [?t :term/category _])]
                :term-without-category)
               (select-errors
                db
                '[:find ?t :where [?t :term/id _] (not [?t :term/normalized _])]
                :term-without-normalized)
               (select-errors
                db
                '[:find ?f :where [?f :form/raw _] (not [?f :form/term _])]
                :form-without-term)
               (select-errors
                db
                '[:find ?t ?c :where [?t :term/category ?c]
                                      (not [?c :class/id _])]
                :missing-category-class)
               (select-errors
                db
                '[:find ?l ?p :where [?l :label/parent ?p]
                                     (not [?p :label/name _])]
                :missing-label-parent)))
        term-rows (d/q '[:find ?tid ?norm ?cid
                         :where [?t :term/id ?tid]
                                [?t :term/normalized ?norm]
                                [?t :term/category ?c]
                                [?c :class/id ?cid]]
                       db)
        by-norm   (group-by second term-rows)
        duplicate-errors
        (for [[norm rows] by-norm
              :when (> (count (distinct (map first rows))) 1)]
          {:type :duplicate-normalized
           :normalized norm
           :terms (vec (distinct (map first rows)))})
        category-errors
        (for [[norm rows] by-norm
              :let [cats (distinct (map #(nth % 2) rows))]
              :when (> (count cats) 1)]
          {:type :normalized-category-conflict
           :normalized norm
           :categories (vec cats)})
        class-rows (d/q '[:find ?cid ?pid
                          :where [?c :class/id ?cid]
                                 [?c :class/parent ?p]
                                 [?p :class/id ?pid]]
                        db)
        class-map  (reduce (fn [m [c p]] (update m c (fnil conj []) p))
                           {}
                           class-rows)
        cycle (class-cycle? (into {} (map (fn [[c ps]]
                                            [c {:parents ps}])
                                          class-map)))]
    {:errors (cond-> (into errors (concat duplicate-errors category-errors))
               cycle (conj {:type :class-cycle :cycle (vec cycle)}))
     :warnings []}))

(defn same-as-closure
  "Return the set of term entity ids equivalent to `term-eid` under symmetric
   :term/same-as edges.  This is computed in Clojure because a symmetric
   recursive Datalog rule loops on cycles."
  [conn term-eid]
  (let [edges (d/q '[:find ?a ?b :where [?a :term/same-as ?b]] (d/db conn))
        adj   (reduce (fn [m [a b]]
                        (-> m
                            (update a (fnil conj #{}) b)
                            (update b (fnil conj #{}) a)))
                      {}
                      edges)]
    (loop [seen #{term-eid} frontier [term-eid]]
      (if-let [t (first frontier)]
        (let [nexts (remove seen (get adj t #{}))]
          (recur (into seen nexts) (into (vec (rest frontier)) nexts)))
        seen))))

(defn validate-cache
  "Validate an ontology cache map against the shared rules."
  [cache config]
  (let [categories (rules/config-categories config)]
    (rules/validate-cache cache categories)))

(defn cache-graph-errors
  "Compare cache entries with the graph's form -> term -> category facts."
  [conn cache]
  (let [db (d/db conn)]
    (into []
          (keep
           (fn [[k v]]
             (when (map? v)
               (if-let [[tid norm cid]
                        (first
                         (d/q '[:find ?tid ?norm ?cid
                               :in $ ?raw
                               :where [?f :form/raw ?raw]
                                      [?f :form/term ?t]
                                      [?t :term/id ?tid]
                                      [?t :term/normalized ?norm]
                                      [?t :term/category ?c]
                                      [?c :class/id ?cid]]
                             db k))]
                 (when (or (not= norm (:normalized v))
                           (not= (some-> cid name) (some-> (:category v) name)))
                   {:type :cache-graph-mismatch
                    :key k
                    :cache v
                    :graph {:term tid :normalized norm :category cid}})
                 {:type :cache-missing-form :key k})))
           cache))))
