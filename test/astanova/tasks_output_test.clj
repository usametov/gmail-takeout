(ns astanova.tasks-output-test
  (:require [astanova.tasks.output :as output]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "task-output" (make-array FileAttribute 0))))

(deftest test-write-task-outputs
  (let [dir (str (temp-dir) "/nested/task")
        paths (output/write-task!
               {:dir dir
                :emails [{:messageId "<x@test>"}]
                :assertions [{:assertion/id "assertion:1"}]
                :entities [{:entity/id "job:1"}]
                :run {:task/id "test"}})]
    (testing "all files are written"
      (is (= [{:messageId "<x@test>"}]
             (json/parse-string (slurp (:emails paths)) true)))
      (is (= [{:assertion/id "assertion:1"}]
             (edn/read-string (slurp (:assertions paths)))))
      (is (= [{:entity/id "job:1"}]
             (edn/read-string (slurp (:entities paths)))))
      (is (= {:task/id "test"} (edn/read-string (slurp (:run paths)))))
      (is (empty? (filter #(.endsWith (.getName %) ".tmp")
                          (.listFiles (java.io.File. dir))))))))

(deftest test-atomic-overwrite
  (let [dir (temp-dir)
        path (str dir "/x.edn")]
    (output/write-edn! path {:v 1})
    (output/write-edn! path {:v 2})
    (is (= {:v 2} (edn/read-string (slurp path))))))
