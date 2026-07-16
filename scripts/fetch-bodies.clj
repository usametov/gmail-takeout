#!/usr/bin/env bb
;; Fetch email bodies from Gmail API for a list of message IDs.
;; Uses gws +read helper which handles MIME parsing, base64 decoding,
;; and HTML-to-text conversion automatically.
;;
;; Usage: ./fetch-bodies.bb email_ids.txt [--edn bodies.edn]
;;
;; Input:  one Gmail message ID per line in a text file
;; Output: EDN file with vector of {:id :subject :from :date :body} maps

(require '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str]
         '[clojure.pprint :as pprint]
         '[cheshire.core :as json])

(defn- check-auth []
  (let [{:keys [exit]} (sh "gws" "gmail" "users" "messages" "list"
                            "--params" (json/generate-string {:userId "me" :maxResults 1}))]
    (when (not= 0 exit)
      (println "GWS is not authenticated.")
      (println)
      (println "To authenticate, run:")
      (println "  gws auth setup")
      (println "or if you have a client_secret.json:")
      (println "  gws auth login")
      (println)
      (System/exit 1))))

(defn- fetch-message [id]
  (println "---" id "---")
  (let [{:keys [exit out err]} (sh "gws" "gmail" "+read" "--id" id "--headers" "--format" "json")]
    (if (zero? exit)
      (json/parse-string out true)
      (do (binding [*out* *err*] (println "gws error for" id ":" err))
          nil))))

(defn- parse-result [id msg]
  (when msg
    {:id      id
     :subject (:subject msg)
     :from    (:from msg)
     :date    (:date msg)
     :body    (:body msg)}))

(defn -main [& args]
  (let [[ids-file & flags] args
        edn-file (some #(when (re-find #"\.edn$" %) %) (rest (drop-while #(not= "--edn" %) flags)))
        ids (->> (slurp ids-file)
                 (str/split-lines)
                 (remove #(or (str/blank? %) (str/starts-with? % "#")))
                 (map #(str/replace % #"[<>]" "")))
        results (atom [])]

    (check-auth)

    (doseq [id ids]
      (when-let [msg (fetch-message id)]
        (when-let [result (parse-result id msg)]
          (swap! results conj result)))
      (Thread/sleep 200))

    (let [data @results]
      (if edn-file
        (spit edn-file (with-out-str (pprint/pprint data)))
        (pprint/pprint data)))

    (println (str "Done. " (count @results) " messages fetched."))))

(apply -main *command-line-args*)