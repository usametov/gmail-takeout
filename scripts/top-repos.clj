#!/usr/bin/env bb
;; Fetch top GitHub repositories by topic (sorted by stars).
;; Generates Markdown with links, star count, language, description, and topics.
;;
;; Usage:
;;   bb scripts/top-repos.clj clojure
;;   bb scripts/top-repos.clj python 30
;;   bb scripts/top-repos.clj machine-learning 50 -o ml-repos.md
;;
;; Options:
;;   <topic>           GitHub topic to search (required)
;;   <limit>           Max repos (default: 50)
;;   -o, --output PATH Write Markdown to file instead of stdout

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; ─── CLI ───────────────────────────────────────────────────────

(defn parse-args [args]
  (loop [args  args
         opts  {:topic nil :limit 50 :output nil}]
    (if-let [arg (first args)]
      (case arg
        "-o"      (recur (nnext args) (assoc opts :output (second args)))
        "--output" (recur (nnext args) (assoc opts :output (second args)))
        (if (re-matches #"\d+" arg)
          (recur (next args) (assoc opts :limit (Integer/parseInt arg)))
          (if (nil? (:topic opts))
            (recur (next args) (assoc opts :topic arg))
            (recur (next args) opts))))
      opts)))

;; ─── GitHub API ─────────────────────────────────────────────────

(defn fetch-top-repos [topic limit]
  (let [url (str "https://api.github.com/search/repositories"
                 "?q=topic:" topic
                 "&sort=stars"
                 "&order=desc"
                 "&per_page=" limit)
        headers {"User-Agent" "Babashka-GitHub-Top-Repos"
                 "Accept" "application/vnd.github.v3+json"}
        headers (if-let [token (System/getenv "GITHUB_TOKEN")]
                  (assoc headers "Authorization" (str "token " token))
                  headers)
        resp (http/get url {:headers headers :throw false})
        status (:status resp)]
    (if (not= 200 status)
      (do (println "Error:" status (get-in resp [:body]))
          (System/exit 1))
      (-> resp :body (json/parse-string true) :items))))

;; ─── Markdown generation ────────────────────────────────────────

(defn repo->markdown [repo]
  (let [{:keys [html_url full_name description stargazers_count language topics]} repo
        desc  (or description "No description provided.")
        stars (format "⭐ %d" stargazers_count)
        lang  (when language (str " `" language "`"))]
    (str "- [" full_name "](" html_url ") " stars
         (when lang (str lang))
         "\n  " desc
         (when (seq topics)
           (str "\n  " (str/join " " (map #(str "`#" % "`") topics)))))))

;; ─── Main ──────────────────────────────────────────────────────

(defn -main [& args]
  (let [{:keys [topic limit output]} (parse-args args)]
    (when (str/blank? topic)
      (println "Usage: bb scripts/top-repos.clj <topic> [limit] [-o output.md]")
      (println "  topic   GitHub topic to search (required)")
      (println "  limit   Max repos (default: 50)")
      (println "  -o PATH Write to file instead of stdout")
      (System/exit 1))

    (println (format "Fetching top %d repos for topic '%s'..." limit topic))
    (let [repos (fetch-top-repos topic limit)]
      (println (str "Found " (count repos) " repos.\n"))
      (let [md (str "# Top " (count repos) " " topic " repositories by stars\n\n"
                   (str/join "\n\n" (map repo->markdown repos))
                   "\n")]
        (if output
          (do (spit output md)
              (println "Written to" output))
          (println md))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
