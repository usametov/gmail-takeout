(ns astanova.mbox-splitter
  "MBOX file splitting: smart boundary detection, message counting.
   Handles large MBOX files (>2GB) by splitting at 'From ' line boundaries
   so each chunk is a valid, independently-ingestible mbox file."
  (:require [clojure.string :as str])
  (:import [java.io File RandomAccessFile]
           [java.nio.file StandardOpenOption]
           [java.nio.channels FileChannel]))

;; ─── Boundary detection ─────────────────────────────────────────

(defn is-from-start?
  "Check if 'From ' at position idx in text is at the start of a line
   and looks like a real mbox delimiter (has an @ on the same line).
   This rejects false positives in binary/Base64 attachment data."
  [^String text ^long idx]
  (and (or (zero? idx)
           (let [prev (dec idx)]
             (when (>= prev 0)
               (let [ch (.charAt text prev)]
                 (or (= ch \newline) (= ch \return))))))
       ;; Verify this looks like a real mbox From_ line by checking for @
       (let [line-end (str/index-of text "\n" idx)]
         (if line-end
           (str/includes? (subs text idx line-end) "@")
           (str/includes? (subs text idx) "@")))))

(defn find-from-in-buffer
  "Search text for 'From ' at start of line. Returns absolute byte pos or nil."
  [^String text ^long buf-pos]
  (loop [idx (str/index-of text "From ")]
    (when idx
      (if (is-from-start? text idx)
        (+ buf-pos idx)
        (recur (str/index-of text "From " (+ idx 5)))))))

(defn find-next-from-boundary
  "Returns byte position of the next 'From ' at start of line, or nil."
  [^RandomAccessFile raf ^long start-pos ^long max-lookahead]
  (let [file-len (.length raf)
        buf-size 16384]
    (.seek raf start-pos)
    (loop [pos start-pos]
      (when (and (< (- pos start-pos) max-lookahead)
                 (< pos file-len))
        (let [to-read (int (min buf-size (- file-len pos)))
              buf (byte-array to-read)
              n (.read raf buf 0 to-read)]
          (when (pos? n)
            (let [text (String. buf 0 n "UTF-8")]
              (or (find-from-in-buffer text pos)
                  (recur (+ pos n))))))))))

(defn copy-range
  "Copy bytes from src channel to dest file."
  [^FileChannel src ^File dest ^long start ^long end]
  (.position src start)
  (let [size (- end start)
        opts (into-array StandardOpenOption
                         [StandardOpenOption/CREATE
                          StandardOpenOption/WRITE
                          StandardOpenOption/TRUNCATE_EXISTING])]
    (with-open [dst (FileChannel/open (.toPath dest) opts)]
      (.transferFrom dst src 0 size))))

;; ─── Split ──────────────────────────────────────────────────────

(defn split-mbox!
  "Split mbox with smart 'From ' boundary detection for safe message alignment.
   Both start and end of each chunk are aligned to 'From ' at start of line,
   ensuring every chunk is a valid mbox file that MboxIterator can read."
  [^String mbox-path ^long chunk-size-mb ^String out-dir]
  (let [chunk-size     (* chunk-size-mb 1024 1024)
        lookahead-bytes (quot chunk-size 2)
        stem (-> (File. mbox-path) .getName (str/replace #"\.mbox$" ""))]
    (.mkdirs (File. out-dir))
    (with-open [raf (RandomAccessFile. mbox-path "r")
                src-channel (.getChannel raf)]
      (let [total (.length raf)]
        (loop [chunk-idx 0
               start-pos 0]
          (when (< start-pos total)
            (let [approx-end (+ start-pos chunk-size)
                  end-pos (if (>= approx-end total)
                            total
                            (or (find-next-from-boundary raf approx-end lookahead-bytes)
                                total))
                  out-file (File. (format "%s/%s.part-%04d.mbox"
                                          out-dir stem (inc chunk-idx)))]
              (when (< start-pos end-pos)
                (println (format "  Writing chunk %d (%d MB target):" (inc chunk-idx) chunk-size-mb))
                (println (format "    %s" (.getName out-file)))
                (copy-range src-channel out-file start-pos end-pos)
                (println (format "    %d bytes" (- end-pos start-pos))))
              (recur (inc chunk-idx) end-pos))))))))

;; ─── Message counting ──────────────────────────────────────────

(defn count-mbox-messages
  "Count messages in an MBOX file by counting 'From ' at start of line.
   Uses the same boundary detection as the splitter."
  [^String path]
  (let [buf-size 16384
        raf (RandomAccessFile. path "r")
        file-len (.length raf)]
    (try
      (loop [pos 0, msg-count 0, leftover ""]
        (if (>= pos file-len)
          msg-count
          (let [to-read (int (min buf-size (- file-len pos)))
                buf (byte-array to-read)
                n (.read raf buf 0 to-read)
                chunk (str leftover (String. buf 0 n "UTF-8"))
                lines (str/split-lines chunk)
                processed-lines (butlast lines)
                new-leftover (last lines)
                from-count (count (filter #(re-find #"^From [^@]+@" %) processed-lines))]
            (recur (+ pos n) (+ msg-count from-count) new-leftover))))
      (finally
        (.close raf)))))
