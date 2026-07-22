#!/usr/bin/env bb
;; Generate Markdown from takeout query output.
;;
;; Reads EDN from stdin (query output), formats emails as Markdown.
;;
;; Usage:
;;   ./takeout -d emails.db query -l "trading" -n 10 --format edn \
;;     | bb scripts/report.clj -o trading.md
;;
;; Options:
;;   -o, --output PATH  Write to file instead of stdout
;;   -n, --limit N      Max emails (default: all from input)

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

;; ─── CLI ───────────────────────────────────────────────────────

(defn parse-opts [args]
  (loop [args args opts {:output nil :limit 0}]
    (if-let [arg (first args)]
      (case arg
        "-o"      (recur (nnext args) (assoc opts :output (second args)))
        "--output" (recur (nnext args) (assoc opts :output (second args)))
        "-n"      (recur (nnext args) (assoc opts :limit (Integer/parseInt (second args))))
        "--limit"  (recur (nnext args) (assoc opts :limit (Integer/parseInt (second args))))
        (do (println "Unknown option:" arg) (System/exit 1)))
      opts)))

;; ─── Helpers ────────────────────────────────────────────────────

(defn items-from-input [input]
  (cond
    (and (map? input) (:results input)) (vec (:results input))
    (and (map? input) (:email/id input)) [input]
    (or (seq? input) (vector? input)) (vec input)
    :else (do (println "Expected EDN query result") (System/exit 1))))

(defn format-date [d]
  (when d
    (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") d)))

(defn truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "...")
    (or s "")))

;; ─── Markdown generators ────────────────────────────────────────

(defn email->markdown [email]
  (let [{:keys [email/id email/subject email/from email/date
                email/labels email/links email/gmail-id
                email/body-truncated]} email
        date-str (format-date date)]
    (str "### " (or subject "(no subject)") "\n"
         "| | |\n"
         "|---|---|\n"
         "| **From** | " (or from "") " |\n"
         "| **Date** | " (or date-str "") " |\n"
         (when gmail-id (str "| **Gmail ID** | `" gmail-id "` |\n"))
         (when (seq labels)
           (str "| **Labels** | " (str/join " " (map #(str "`" % "`") labels)) " |\n"))
         (when (seq links)
           (str "| **Links** |\n"
                (str/join "\n" (map (fn [url] (str "| | " url " |")) links))
                "\n"))
         "\n"
         (when body-truncated
           (str (truncate body-truncated 500) "\n\n"))
         "---\n")))

;; ─── Main ──────────────────────────────────────────────────────

(defn -main [& args]
  (let [opts    (parse-opts args)
        out-file (:output opts)
        limit   (:limit opts)
        input   (edn/read-string (slurp *in*))
        items   (items-from-input input)
        items   (cond->> items (pos? limit) (take limit))]

    (let [md (str "# Email Report\n\n"
                 (str/join "\n" (map email->markdown items))
                 "\n*" (count items) " emails*")]
      (if out-file
        (do (spit out-file md) (println "Written to" out-file))
        (println md)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
