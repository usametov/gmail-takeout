(ns astanova.ontology-rules-test
  (:require [astanova.ontology.rules :as rules]
            [clojure.test :refer [deftest is testing]]))

(def categories ["companies" "roles" "fields" "job_types" "locations"])

(deftest test-normalization-and-categories
  (testing "category names and synonyms normalize"
    (is (= "job_types" (rules/normalize-category-name "Job Types")))
    (is (= "companies" (rules/normalize-category categories "Company")))
    (is (= "job_types" (rules/normalize-category categories "job-type")))
    (is (nil? (rules/normalize-category categories "unknown"))))
  (testing "near-duplicate keys collapse spacing/hyphen/case variants"
    (is (= (rules/normalization-key "Liquid AI")
           (rules/normalization-key "LiquidAI")))
    (is (= (rules/normalization-key "HTTP-Kit")
           (rules/normalization-key "http kit")))
    (is (not= (rules/normalization-key "C++")
              (rules/normalization-key "C#")))
    (is (nil? (rules/normalization-key nil)))))

(deftest test-preferred-canonicalization
  (testing "preferred normalized spelling is deterministic"
    (is (= "Liquid AI"
           (rules/preferred-normalized ["LiquidAI" "Liquid AI" "Liquid AI"])))
    (is (= "HTTP-Kit"
           (rules/preferred-normalized ["HttpKit" "HTTP-Kit"]))))
  (testing "preferred category is deterministic by frequency then first"
    (is (= "companies"
           (rules/preferred-category ["companies" "roles" "companies"])))
    (is (= "roles"
           (rules/preferred-category ["roles" "companies"])))))

(deftest test-noise-rules
  (let [config {:df_threshold 0.25
                :user_allow_list ["Toronto"]
                :user_stop_list ["View Job"]
                :standalone_generic ["Senior"]
                :auto_detected_df_stops ["Exciting Opportunity"]}
        sets (rules/filter-sets config)]
    (testing "allow list wins over every noise rule"
      (is (nil? (rules/noise-reason sets config "Toronto" 1.0))))
    (testing "configured and statistical noise"
      (is (= :user-stop-list (rules/noise-reason sets config "View Job" nil)))
      (is (= :standalone-generic (rules/noise-reason sets config "Senior" nil)))
      (is (= :df-stop (rules/noise-reason sets config "Exciting Opportunity" nil)))
      (is (= :df-threshold (rules/noise-reason sets config "Common Term" 0.25))))
    (testing "structural noise"
      (is (= :numeric (rules/noise-reason sets config "123-456" nil)))
      (is (= :too-long (rules/noise-reason sets config (apply str (repeat 41 "a")) nil)))
      (is (= :sentence-fragment
             (rules/noise-reason sets config "Sentence. Next" nil)))
      (is (= :url-fragment
             (rules/noise-reason sets config "https://example.com" nil)))
      (is (= :too-short (rules/noise-reason sets config "Go" nil)))
      (is (nil? (rules/noise-reason sets config "AI" nil))))))

(deftest test-config-normalization
  (testing "config defaults and aliases"
    (let [config (rules/normalize-config {:df-threshold "0.4"
                                          :categories ["Company" "Role"]})]
      (is (= 0.4 (:df_threshold config)))
      (is (= ["companies" "roles"] (rules/config-categories config)))
      (is (= 1 (:schema_version config))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rules/config-categories {:categories []})))
    (let [aliases (rules/alias-index
                   {"LiquidAI" {:normalized "Liquid AI" :category "companies"}
                    "toronto" "Toronto"}
                   categories)]
      (is (= {:normalized "Liquid AI" :category "companies"}
             (get aliases "liquidai")))
      (is (= {:normalized "Toronto" :category nil}
             (get aliases "toronto"))))))

(deftest test-cache-validation
  (testing "valid dual-key cache passes"
    (let [cache {"Liquid AI" {:category "companies" :normalized "Liquid AI"}
                 "liquid ai" {:category "companies" :normalized "Liquid AI"}}
          result (rules/validate-cache cache categories)]
      (is (= 2 (:count result)))
      (is (empty? (:conflicts result)))
      (is (empty? (:missing-lowercase result)))
      (is (empty? (:invalid result)))))
  (testing "category conflicts and missing lowercase keys are reported"
    (let [cache {"Liquid AI" {:category "companies" :normalized "Liquid AI"}
                 "liquidai" {:category "roles" :normalized "LiquidAI"}}
          result (rules/validate-cache cache categories)]
      (is (= 1 (count (:conflicts result))))
      (is (= ["Liquid AI"] (:missing-lowercase result)))
      (is (= 1 (count (:variants result))))))
  (testing "invalid entries are reported"
    (let [result (rules/validate-cache
                  {"x" {:category "nope" :normalized ""}
                   "y" "not-a-map"}
                  categories)]
      (is (= 2 (count (:invalid result)))))))
