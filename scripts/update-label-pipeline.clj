#!/usr/bin/env bb
;; Full pipeline: update Gmail IDs → fetch bodies → store bodies
;;
;; Usage: bb scripts/update-label-pipeline.clj <label> [options]
;;
;; Options:
;;   --no-dry-run   Actually call gws API and transact to DB
;;   --force        Re-fetch Gmail IDs even if already set
;;   -n, --limit N  Max emails to process (0 = all, default)
;;
;; Example:
;;   bb scripts/update-label-pipeline.clj "video/youtube"
;;   bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run
;;   bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run --force -n 500
;;
;; Steps:
;;   1. Query emails by label, map Message-IDs → Gmail IDs via gws
;;   2. Update database with Gmail IDs
;;   3. Fetch email bodies for records with empty body via gws
;;   4. Update database with bodies (HTML → text via Hickory)

(require '[babashka.process :refer [sh shell]]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

;; ─── CLI ───────────────────────────────────────────────────────

(defn parse-int [s] (Long/parseLong s))

(defn parse-opts [args]
  (loop [args args
         opts {:no-dry-run false :force false :limit 0}]
    (if-let [arg (first args)]
      (case arg
        "--no-dry-run" (recur (next args) (assoc opts :no-dry-run true))
        "--force"      (recur (next args) (assoc opts :force true))
        "-n"           (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        "--limit"      (recur (nnext args) (assoc opts :limit (parse-int (second args))))
        (do (println "Unknown option:" arg) (System/exit 1)))
      opts)))

;; ─── Paths ──────────────────────────────────────────────────────

(def home    (System/getProperty "user.home"))
(def db      (str home "/Documents/Takeout/sent-emails-latest.db"))
(def outdir  (str home "/Documents/Takeout"))
(def takeout (str home "/code/takeout/takeout"))
(def scripts (str home "/code/takeout/scripts"))

;; ─── Main ──────────────────────────────────────────────────────

(defn -main [& args]
  (let [[label & rest] args
        opts (parse-opts rest)]
    (when (or (nil? label) (str/blank? label))
      (println "Usage: bb scripts/update-label-pipeline.clj <label> [options]")
      (println "  label          Gmail label, e.g. 'video/youtube'")
      (println "  --no-dry-run   Actually call gws API and transact to DB")
      (println "  --force        Re-fetch Gmail IDs even if already set")
      (println "  -n, --limit N  Max emails (default: all)")
      (System/exit 1))

    (let [no-dry?   (:no-dry-run opts)
          force?    (:force opts)
          limit     (:limit opts)

          label-safe (str/replace label "/" "-")
          map-file   (str outdir "/" label-safe "-map-ids.edn")
          bodies-file (str outdir "/" label-safe "-bodies.edn")]

      (println "=== Label:" label "===")
      (println "  DB:      " db)
      (println "  Map:     " map-file)
      (println "  Bodies:  " bodies-file)
      (println "  Dry-run: " (not no-dry?))
      (when force? (println "  Force:   yes"))
      (when (pos? limit) (println "  Limit:   " limit))
      (println)

      ;; ─── Step 1: Map Message-IDs → Gmail IDs ─────────────────

      (println "--- Step 1: Map Message-IDs → Gmail IDs ---")
      (let [cmd (str takeout " -d " db " query -l '" label "' -n 0 --format edn"
                    " | bb " scripts "/map_gmail_ids.clj"
                    " -o " map-file
                    (when no-dry? " --no-dry-run")
                    (when force? " --force"))
            {:keys [exit err]} (shell {:out :inherit :err :inherit} cmd)]
        (when (not= 0 exit)
          (println "ERROR: map_gmail_ids failed (exit" exit ")")
          (when err (println err))
          (System/exit 1)))

      (when-not (fs/exists? map-file)
        (println "ERROR: map file not created:" map-file)
        (System/exit 1))

      (println)
      (println "  Output:" map-file)
      (println)

      ;; ─── Step 2: Update Gmail IDs in database ─────────────────

      (println "--- Step 2: Update Gmail IDs ---")
      (let [args (cond-> [(str takeout) "-d" db "update-message-ids" "--from" map-file]
                   (not no-dry?) (conj "--dry-run")
                   force?        (conj "--force"))
            {:keys [exit out err]} (apply sh args)]
        (println out)
        (when err (binding [*out* *err*] (println err)))
        (when (not= 0 exit)
          (println "ERROR: update-message-ids failed (exit" exit ")")
          (System/exit 1)))

      (println)

      ;; ─── Step 3: Fetch email bodies ────────────────────────────

      (println "--- Step 3: Fetch email bodies ---")
      (let [cmd (str takeout " -d " db " query -l '" label "' -n 0 --format edn"
                    " | bb " scripts "/fetch-bodies.clj"
                    " -o " bodies-file
                    (when no-dry? " --no-dry-run"))
            {:keys [exit err]} (shell {:out :inherit :err :inherit} cmd)]
        (when (not= 0 exit)
          (println "ERROR: fetch-bodies failed (exit" exit ")")
          (when err (println err))
          (System/exit 1)))

      (when-not (fs/exists? bodies-file)
        (println "ERROR: bodies file not created:" bodies-file)
        (System/exit 1))

      (println)
      (println "  Output:" bodies-file)
      (println)

      ;; ─── Step 4: Update bodies in database ────────────────────

      (println "--- Step 4: Update bodies ---")
      (let [args (cond-> [(str takeout) "-d" db "update-bodies" "--from" bodies-file]
                   (not no-dry?) (conj "--dry-run"))
            {:keys [exit out err]} (apply sh args)]
        (println out)
        (when err (binding [*out* *err*] (println err)))
        (when (not= 0 exit)
          (println "ERROR: update-bodies failed (exit" exit ")")
          (System/exit 1)))

      (println)
      (println "=== Pipeline complete for label:" label "==="))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
