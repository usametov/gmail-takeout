(ns astanova.tasks.assertions
  "Pure assertion construction, stable IDs, and revision IDs."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(def statuses
  #{:candidate :approved :rejected :corrected :superseded :conflicted :stale})

(def subject-types #{:email :entity})

(defn sha256 [s]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str s) "UTF-8"))]
    (format "%064x" (BigInteger. 1 bytes))))

(defn- canonical [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [k (canonical v)]))
                   x)
    (vector? x) (mapv canonical x)
    (set? x) (vec (sort (map pr-str x)))
    :else x))

(defn assertion-id
  "Stable identity for a field-value claim."
  [{:assertion/keys [subject-type subject-id field extractor-id
                     extractor-version locator raw-value]}]
  (str "assertion:"
       (sha256 (str (name subject-type) "|"
                    subject-id "|"
                    field "|"
                    extractor-id "|"
                    extractor-version "|"
                    (pr-str (canonical locator)) "|"
                    (pr-str (canonical raw-value))))))

(defn revision-id
  [assertion-id number action]
  (str "assertion-revision:"
       (sha256 (str assertion-id "|" number "|" (name action)))))

(defn- require! [m k]
  (when (nil? (get m k))
    (throw (ex-info (str "Assertion missing required key: " k) {:assertion m})))
  (get m k))

(defn make
  "Build a candidate assertion.  Accepts :now for deterministic tests."
  [m]
  (let [subject-type (require! m :assertion/subject-type)
        subject-id   (require! m :assertion/subject-id)
        field        (require! m :assertion/field)
        extractor-id (require! m :assertion/extractor-id)
        extractor-v  (require! m :assertion/extractor-version)
        method       (require! m :assertion/method)
        raw-value    (get m :assertion/raw-value)
        now          (or (:now m) (Instant/now))
        base         {:assertion/subject-type subject-type
                      :assertion/subject-id subject-id
                      :assertion/field field
                      :assertion/raw-value raw-value
                      :assertion/value (or (:assertion/value m) raw-value)
                      :assertion/value-type (or (:assertion/value-type m) :string)
                      :assertion/multi (boolean (:assertion/multi m))
                      :assertion/status (or (:assertion/status m) :candidate)
                      :assertion/revision (or (:assertion/revision m) 1)
                      :assertion/confidence (:assertion/confidence m)
                      :assertion/task-id (:assertion/task-id m)
                      :assertion/task-version (:assertion/task-version m)
                      :assertion/run-id (:assertion/run-id m)
                      :assertion/extractor-id extractor-id
                      :assertion/extractor-version extractor-v
                      :assertion/method method
                      :assertion/locator (:assertion/locator m)
                      :assertion/snippet (:assertion/snippet m)
                      :assertion/query (:assertion/query m)
                      :assertion/observed-at (or (:assertion/observed-at m) now)
                      :assertion/created-at (or (:assertion/created-at m) now)
                      :assertion/updated-at (or (:assertion/updated-at m) now)
                      :assertion/llm (:assertion/llm m)
                      :assertion/extra (:assertion/extra m)}]
    (when-not (contains? subject-types subject-type)
      (throw (ex-info "Invalid assertion subject type"
                      {:subject-type subject-type :allowed subject-types})))
    (when-not (keyword? field)
      (throw (ex-info "Assertion field must be a keyword" {:field field})))
    (when (str/blank? (str extractor-id))
      (throw (ex-info "Assertion extractor id must be non-blank"
                      {:extractor-id extractor-id})))
    (when-not (and (int? extractor-v) (pos? extractor-v))
      (throw (ex-info "Assertion extractor version must be a positive integer"
                      {:extractor-version extractor-v})))
    (when-not (contains? statuses (:assertion/status base))
      (throw (ex-info "Invalid assertion status"
                      {:status (:assertion/status base) :allowed statuses})))
    (assoc base :assertion/id (or (:assertion/id m) (assertion-id base)))))

(defn candidate? [a] (= :candidate (:assertion/status a)))
