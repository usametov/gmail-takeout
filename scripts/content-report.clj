#!/usr/bin/env bb
;; Generate Markdown report from takeout content command output.
;;
;; Reads EDN from stdin (content command output), groups content by
;; type and host, and generates a readable Markdown report.
;;
;; Usage:
;;   ./takeout -d emails.db content -l "trading" --format edn \
;;     | bb scripts/content-report.clj -o content-report.md
;;
;; Options:
;;   -o, --output PATH  Write to file instead of stdout

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

;; ─── CLI ───────────────────────────────────────────────────────

(defn parse-opts [args]
  (loop [args args opts {:output nil}]
    (if-let [arg (first args)]
      (case arg
        "-o"      (recur (nnext args) (assoc opts :output (second args)))
        "--output" (recur (nnext args) (assoc opts :output (second args)))
        (do (println "Unknown option:" arg) (System/exit 1)))
      opts)))

;; ─── Helpers ────────────────────────────────────────────────────

(defn format-date [d] (when d (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") d)))

(defn type-icon [t]
  (case t :paper "📄" :git-repo "📦" :video-transcript "🎬" "📎"))

(defn host-icon [host]
  (cond (str/includes? (or host "") "arxiv") "arxiv"
        (str/includes? (or host "") "github") "github"
        (str/includes? (or host "") "youtube") "youtube"
        :else (or host "unknown")))

(defn body-snippet [c]
  (let [body (:content/body c)
        t    (:content/type c)]
    (when body
      (case t
        :paper (or (second (re-find #"(?s)<summary>(.*?)</summary>" body)) body)
        body))))

;; ─── Markdown ──────────────────────────────────────────────────

(defn item->markdown [item]
  (let [{:keys [content subject date]} item
        url  (:content/url content)
        host (:content/host content)
        t    (:content/type content)
        icon (type-icon t)]
    (str "### " icon " " (or subject "(no subject)") "\n"
         "| | |\n"
         "|---|---|\n"
         "| **URL** | [" (host-icon host) "](" url ") |\n"
         "| **Date** | " (or (format-date date) "") " |\n"
         "| **Type** | " (name (or t :unknown)) " |\n"
         (when-let [body (:content/body content)]
           (str "\n" body "\n"))
         "\n---\n")))

;; ─── Main ──────────────────────────────────────────────────────

(defn -main [& args]
  (let [opts    (parse-opts args)
        out-file (:output opts)
        input   (edn/read-string (slurp *in*))
        items   (vec input)]

    (let [by-type (group-by (comp :content/type :content) items)
          md (str "# Content Report\n\n"
                 (str (count items) " items across " (count by-type) " types\n\n")
                 (str/join "\n" (map item->markdown items)))]
      (if out-file
        (do (spit out-file md) (println "Written to" out-file))
        (println md)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
