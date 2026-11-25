(ns astanova.tasks.manifest
  "Load and validate a replayable gws task manifest (task.edn)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def filter-ops
  #{:matches-any :matches-all :not-matches :equals :in
    :present :absent :min-length :and :or :not})

(def field-backends #{:heuristic :local-model :hybrid})

(defn- error [path message data]
  {:path path :message message :data data})

(defn- blank? [x] (str/blank? (str x)))

(defn- validate-field [errors field]
  (let [path [:extract :fields]
        id   (:field/id field)]
    (cond-> errors
      (not (keyword? id))
      (conj (error (conj path :field/id) "must be a keyword" id))

      (not (keyword? (:field/type field)))
      (conj (error (conj path :field/type) "must be a keyword" (:field/type field)))

      (and (:field/backend field)
           (not (field-backends (:field/backend field))))
      (conj (error (conj path :field/backend)
                   "must be :heuristic, :local-model, or :hybrid"
                   (:field/backend field))))))

(defn- validate-rule [errors rule]
  (let [path [:filter :rules]
        id   (:rule/id rule)]
    (cond-> errors
      (not (keyword? id))
      (conj (error (conj path :rule/id) "must be a keyword" id))

      (not (contains? filter-ops (:op rule)))
      (conj (error (conj path :op) "unsupported operator" (:op rule)))

      (and (#{:matches-any :matches-all :not-matches} (:op rule))
           (not (and (sequential? (:terms rule)) (seq (:terms rule)))))
      (conj (error (conj path :terms) "must be a non-empty collection" (:terms rule))))))

(defn validate
  "Return {:errors [...] :warnings [...]}; empty :errors means valid."
  [m]
  (let [errors
        (cond-> []
          (not (map? m))
          (conj (error [] "manifest must be a map" m))

          (blank? (:task/id m))
          (conj (error [:task/id] "must be a non-blank string" (:task/id m)))

          (not (pos? (or (:task/version m) 0)))
          (conj (error [:task/version] "must be a positive integer" (:task/version m)))

          (not (map? (:fetch m)))
          (conj (error [:fetch] "must be a map" (:fetch m)))

          (and (map? (:fetch m)) (blank? (get-in m [:fetch :query])))
          (conj (error [:fetch :query] "must be a non-blank Gmail query" (get-in m [:fetch :query])))

          (not (map? (:extract m)))
          (conj (error [:extract] "must be a map" (:extract m)))

          (and (map? (:extract m)) (blank? (get-in m [:extract :extractor/id])))
          (conj (error [:extract :extractor/id] "must be non-blank" (get-in m [:extract :extractor/id])))

          (and (map? (:extract m))
               (not (pos? (or (get-in m [:extract :extractor/version]) 0))))
          (conj (error [:extract :extractor/version] "must be a positive integer"
                       (get-in m [:extract :extractor/version])))

          (and (map? (:extract m))
               (not (and (sequential? (get-in m [:extract :fields]))
                         (seq (get-in m [:extract :fields])))))
          (conj (error [:extract :fields] "must be a non-empty collection"
                       (get-in m [:extract :fields])))

          (not (contains? #{:include :exclude} (get-in m [:filter :mode])))
          (conj (error [:filter :mode] "must be :include or :exclude"
                       (get-in m [:filter :mode])))

          (not (map? (:output m)))
          (conj (error [:output] "must be a map" (:output m)))

          (and (map? (:llm m)) (:llm/enabled m)
               (or (blank? (get-in m [:llm :provider]))
                   (blank? (get-in m [:llm :model]))
                   (blank? (get-in m [:llm :prompt-id]))))
          (conj (error [:llm] "enabled LLM requires :provider, :model, and :prompt-id" (:llm m))))]
    (let [fields (get-in m [:extract :fields])
          field-errors (mapcat #(validate-field [] %) (when (sequential? fields) fields))
          field-ids (keep :field/id (when (sequential? fields) fields))
          duplicate-fields (->> field-ids frequencies (keep (fn [[k n]] (when (> n 1) k))))
          rules (get-in m [:filter :rules])
          rule-errors (mapcat #(validate-rule [] %) (when (sequential? rules) rules))
          rule-ids (keep :rule/id (when (sequential? rules) rules))
          duplicate-rules (->> rule-ids frequencies (keep (fn [[k n]] (when (> n 1) k))))
          include-if (get-in m [:filter :include-if])
          include-ids (when (map? include-if)
                        (concat (:all include-if) (:any include-if)))
          unknown-include (remove (set rule-ids) include-ids)
          local-field? (some #(= :local-model (:field/backend %))
                             (when (sequential? fields) fields))
          errors (into errors (concat field-errors rule-errors))]
      {:errors (cond-> errors
                 (seq duplicate-fields)
                 (conj (error [:extract :fields] "duplicate field ids" (vec duplicate-fields)))

                 (seq duplicate-rules)
                 (conj (error [:filter :rules] "duplicate rule ids" (vec duplicate-rules)))

                 (seq unknown-include)
                 (conj (error [:filter :include-if] "references unknown rule ids" (vec unknown-include)))

                 (and local-field? (not (get-in m [:llm :enabled])))
                 (conj (error [:llm :enabled] "local-model fields require explicit LLM enablement" nil)))
       :warnings []})))

(defn- default-output [task-id]
  {:dir (str "data/tasks/" task-id)
   :emails "emails.json"
   :assertions "assertions.edn"
   :entities "entities.edn"
   :run "run.edn"})

(defn normalize
  "Fill non-destructive defaults.  Does not validate."
  [m]
  (let [task-id (:task/id m)]
    (-> m
        (update :filter #(merge {:mode :include} %))
        (update :output #(merge (default-output task-id) %))
        (update :import #(merge {:emails true :assertions false :entities false} %))
        (update :llm #(merge {:enabled false} %)))))

(defn load-task
  "Read task.edn, validate it, and return the normalized manifest.
   Throws ex-info with :errors when invalid."
  [path]
  (let [raw (edn/read-string (slurp path))
        result (validate raw)]
    (when (seq (:errors result))
      (throw (ex-info (str "Invalid task manifest: " path)
                      {:path path :errors (:errors result)})))
    (normalize raw)))

(defn summary [m]
  {:task/id (:task/id m)
   :task/version (:task/version m)
   :fetch/query (get-in m [:fetch :query])
   :extract/fields (mapv :field/id (get-in m [:extract :fields]))
   :filter/mode (get-in m [:filter :mode])
   :filter/rules (mapv :rule/id (get-in m [:filter :rules]))
   :output/dir (get-in m [:output :dir])})
