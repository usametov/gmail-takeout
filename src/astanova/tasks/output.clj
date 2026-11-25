(ns astanova.tasks.output
  "Atomic writers for task email/assertion/entity/run outputs."
  (:require [cheshire.core :as json]
            [clojure.pprint :as pp])
  (:import [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- ensure-parent! [path]
  (let [parent (.getParent (java.io.File. ^String path))]
    (when parent
      (Files/createDirectories (.toPath (java.io.File. ^String parent))
                               (make-array FileAttribute 0)))))

(defn write-atomic!
  "Write text to path via a sibling temp file and atomic move."
  [path text]
  (ensure-parent! path)
  (let [target (java.io.File. ^String path)
        parent (or (.getParentFile target) (java.io.File. "."))
        tmp    (java.io.File/createTempFile "task-" ".tmp" parent)]
    (try
      (spit tmp text)
      (try
        (Files/move (.toPath tmp) (.toPath target)
                    (into-array java.nio.file.CopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch Exception _
          (Files/move (.toPath tmp) (.toPath target)
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (when (.exists tmp)
          (.delete tmp))))))

(defn serialize-edn [data]
  (with-out-str (pp/pprint data)))

(defn write-edn! [path data]
  (write-atomic! path (serialize-edn data))
  path)

(defn write-json! [path data]
  (write-atomic! path (json/generate-string data {:pretty true}))
  path)

(defn write-task!
  "Write task outputs under output-dir.  Returns a map of paths.

   Input keys: :emails (vector), :assertions (vector), :entities (vector),
   :run (map), optional :dir, and optional :output filename map.
   Missing outputs are skipped."
  [{:keys [dir emails assertions entities run output] :as task}]
  (let [dir (or dir (:output/dir task))
        _ (when (nil? dir)
            (throw (ex-info "Task output directory is required" {:task task})))
        filename (fn [k default] (or (get output k) default))
        emails-path (when emails (str dir "/" (filename :emails "emails.json")))
        assertions-path (when assertions (str dir "/" (filename :assertions "assertions.edn")))
        entities-path (when entities (str dir "/" (filename :entities "entities.edn")))
        run-path (when run (str dir "/" (filename :run "run.edn")))]
    (when emails-path (write-json! emails-path emails))
    (when assertions-path (write-edn! assertions-path assertions))
    (when entities-path (write-edn! entities-path entities))
    (when run-path (write-edn! run-path run))
    {:dir dir
     :emails emails-path
     :assertions assertions-path
     :entities entities-path
     :run run-path}))
