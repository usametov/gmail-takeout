#!/usr/bin/env bb
;; Fetch content for URLs found in emails: arxiv abstracts, GitHub READMEs,
;; YouTube transcripts. Uses babashka.http-client for all HTTP calls.
;;
;; Reads EDN query output from stdin, processes emails with :email/links,
;; fetches content from the appropriate source, and writes results as EDN.
;;
;; Usage:
;;   ./takeout -d emails.db query -l "video/youtube" --format edn \
;;     | bb scripts/fetch-content.clj -o content.edn --no-dry-run
;;
;; Output EDN format:
;;   {"<email-id>" {:links {"https://arxiv.org/..." {:type :arxiv :summary "..."}
;;                          "https://github.com/..." {:type :github :readme "..."}
;;                          "https://youtube.com/..." {:type :youtube :transcript "..."}}
;;                  ...}, ...}

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.process :refer [sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pprint])

;; ─── CLI ───────────────────────────────────────────────────────

(defn parse-int [s] (Long/parseLong s))

(defn parse-opts [args]
  (loop [args args
         opts {:out-file "content.edn" :delay 1000 :limit 0 :dry-run true}]
    (if-let [arg (first args)]
      (case arg
        "-o"      (recur (nnext args) (assoc opts :out-file (second args)))
        "--out-file" (recur (nnext args) (assoc opts :out-file (second args)))
        "-d"      (recur (nnext args) (assoc opts :delay (parse-int (second args))))
        "--delay" (recur (nnext args) (assoc opts :delay (parse-int (second args))))
        "-n"      (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        "--limit" (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        "--no-dry-run" (recur (next args) (assoc opts :dry-run false))
        (do (println "Unknown option:" arg) (System/exit 1)))
      opts)))

;; ─── URL classifiers / extractors ────────────────────────────────

(defn classify-url [url]
  (cond
    (str/includes? url "arxiv.org")    :arxiv
    (str/includes? url "github.com")   :github
    (or (str/includes? url "youtube.com") (str/includes? url "youtu.be")) :youtube
    :else :unknown))

(defn arxiv-id [url]
  (when-let [m (re-find #"arxiv\.org/(?:abs|pdf)/([\d\.]+)(?:v\d+)?" url)] (second m)))

(defn github-repo [url]
  (when-let [m (re-find #"github\.com/([^/]+/[^/\s?#]+)" url)]
    (str/replace (second m) #"/$" "")))

(defn youtube-video-id [url]
  (cond
    (re-find #"youtu\.be/([a-zA-Z0-9_-]+)" url) (second (re-find #"youtu\.be/([a-zA-Z0-9_-]+)" url))
    (re-find #"[?&]v=([a-zA-Z0-9_-]+)" url)     (second (re-find #"[?&]v=([a-zA-Z0-9_-]+)" url))))

;; ─── HTTP helpers ───────────────────────────────────────────────

(defn- http-get [url & {:as opts}]
  (try (http/get url opts)
       (catch Exception e {:status 0 :body (.getMessage e)})))

;; ─── Content fetchers ───────────────────────────────────────────

(defn fetch-arxiv [arxiv-id]
  (try
    (let [url  (str "http://export.arxiv.org/api/query?id_list=" arxiv-id "&max_results=1")
          resp (http-get url :headers {"Accept" "application/atom+xml"})
          body (:body resp)]
      (if (and (= 200 (:status resp)) (not (str/blank? body)))
        {:type :arxiv :xml body}
        {:type :arxiv :error (str "fetch failed (status=" (:status resp) ")")}))
    (catch Exception e {:type :arxiv :error (str e)})))

(defn fetch-github-readme [repo]
  (try
    (letfn [(try-branch [branch]
              (some (fn [suffix]
                      (let [url  (str "https://raw.githubusercontent.com/" repo "/refs/heads/" branch "/README" suffix)
                            resp (http-get url)
                            body (:body resp)]
                        (when (and (= 200 (:status resp)) body (not (str/includes? body "404: Not Found")))
                          (subs body 0 (min 5000 (count body))))))
                    [".md" ".txt" ".rst" ".org" ""]))]
      (if-let [readme (or (try-branch "master") (try-branch "main"))]
        {:type :github :readme readme}
        {:type :github :error "README not found"}))
    (catch Exception e {:type :github :error (str e)})))

(defn fetch-youtube-transcript [video-id]
  (try
    (let [url (str "https://www.youtube.com/watch?v=" video-id)
          {:keys [out exit err]} (sh "yt-dlp" "--dump-json" "--skip-download" url)]
      (if (zero? exit)
        (let [data  (json/parse-string out true)
              title (:title data)
              desc  (:description data)
              dur   (:duration data)]
          {:type :youtube
           :title title
           :description (subs (or desc "") 0 (min 2000 (count (or desc ""))))
           :duration dur})
        {:type :youtube :error (str "yt-dlp failed: " (or err "unknown"))}))
    (catch Exception e {:type :youtube :error (str e)})))

(defn fetch-content [url]
  (let [t (classify-url url)]
    (case t
      :arxiv   (if-let [id (arxiv-id url)] (fetch-arxiv id) {:type :arxiv :error "bad id"})
      :github  (if-let [repo (github-repo url)] (fetch-github-readme repo) {:type :github :error "bad repo"})
      :youtube (if-let [vid (youtube-video-id url)] (fetch-youtube-transcript vid) {:type :youtube :error "bad id"})
      {:type :unknown})))

;; ─── Input parsing ─────────────────────────────────────────────

(defn items-from-input [input]
  (cond
    (and (map? input) (:results input)) (vec (:results input))
    (and (map? input) (:email/id input)) [input]
    (or (seq? input) (vector? input)) (vec input)
    :else (do (println "Expected EDN query result, got:" (type input)) (System/exit 1))))

(defn needs-fetch? [item]
  (and (seq (:email/links item)) (not (:email/content item))))

;; ─── Main ──────────────────────────────────────────────────────

(defn -main [& args]
  (let [opts      (parse-opts args)
        out-file  (:out-file opts)
        delay-ms  (:delay opts)
        limit     (:limit opts)
        dry-run?  (:dry-run opts)

        input       (edn/read-string (slurp *in*))
        all-items   (items-from-input input)
        need-fetch  (filter needs-fetch? all-items)
        to-fetch    (cond->> need-fetch (pos? limit) (take limit))
        skip-count  (- (count all-items) (count to-fetch))]

    (println "Processing" (count to-fetch) "emails" (str "(skip: " skip-count ")" )
             (str "(delay=" delay-ms "ms" (when dry-run? " [DRY-RUN]") ")"))
    (println "Output:" out-file)
    (println)

    (let [results    (atom {})
          start-time (System/currentTimeMillis)
          url-count  (atom 0)]

      (doseq [[i item] (map-indexed vector to-fetch)]
        (let [n     (inc i)
              links (:email/links item)
              eid   (:email/id item)]
          (when (and (> (count to-fetch) 5) (zero? (mod n 5)))
            (let [elapsed (- (System/currentTimeMillis) start-time)
                  rate    (double (/ n (max 1 (/ elapsed 1000.0))))]
              (println (format "  [%d/%d] %.0f/s ~%.0fs remaining"
                               n (count to-fetch) rate
                               (/ (max 0 (- (count to-fetch) n)) rate)))))

          (if dry-run?
            (swap! results assoc eid {:links (zipmap links (repeat {:type :dry-run}))})
            (let [fetched (reduce-kv
                           (fn [m url _] (assoc m url (fetch-content url)))
                           {}
                           (zipmap links (repeat nil)))]
              (swap! url-count + (count fetched))
              (swap! results assoc eid {:links fetched})))
          (Thread/sleep delay-ms)))

      (println (str "\nWriting " (count @results) " entries (" @url-count " URLs) to " out-file))
      (spit out-file (with-out-str (pprint/pprint @results)))

      (let [elapsed (quot (- (System/currentTimeMillis) start-time) 1000)]
        (println "Done." (str "(elapsed: " (quot elapsed 60) "m " (mod elapsed 60) "s)"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
