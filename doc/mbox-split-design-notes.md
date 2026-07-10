# Mbox Splitter Design Document

## 1. Overview

**Purpose**: Split a large `.mbox` file into smaller chunks (default 500 MB) without breaking individual email messages.

**Key Requirement**:  
Find safe split points by locating the next `From` boundary after the target byte offset. Both chunk start and end are aligned to `From` boundaries, so every chunk is a valid mbox file.

**Design Goals**:

- High performance for large files (> several GB) using NIO zero-copy
- Memory efficient — never loads more than 16 KB at a time
- Correct mbox format preservation — each chunk starts with `From sender@domain`
- Reject false positive `From` patterns inside binary/Base64 attachment data

## 2. Mbox Format Summary

- Messages are concatenated.
- Each message **must start** with a line beginning with `"From "` (5 chars) followed by an email address and date.
- Lines inside messages starting with `From` are escaped (`>From`) in mboxrd variant.
- Reliable delimiter: `"From "` at start of line containing an `@` character.

## 3. Architecture

**Components**:

1. **`is-from-start?`** — Validates that `"From "` occurs at start of a line AND contains an `@` on the same line (rejects false positives in binary data).
2. **`find-from-in-buffer`** — Searches a 16 KB text buffer for `"From "` at line start, returns absolute byte position.
3. **`find-next-from-boundary`** — Scans the file in 16 KB chunks, delegating to `find-from-in-buffer` for each chunk.
4. **`copy-range`** — Uses NIO `FileChannel.transferFrom` for fast zero-copy writes.
5. **`split-mbox!`** — Main loop: for each chunk, finds the next `From` boundary after the target offset, then copies from the previous boundary to the found boundary.

**Data Flow**:

```
Input .mbox (large file)
    ↓
split-mbox! → for each chunk:
  1. start-pos = previous chunk's end boundary
  2. approx-end = start-pos + chunk-size
  3. end-pos = find-next-from-boundary(approx-end)
  4. copy-range(src, out-file, start-pos, end-pos)
  5. recur with start-pos = end-pos
```

## 4. Implementation

The splitter lives in **`src/astanova/cli.clj`** as part of the `takeout` CLI tool.

### Boundary detection

```clojure
(defn- is-from-start?
  "Check if 'From ' at position idx in text is at the start of a line
   and looks like a real mbox delimiter (has an @ on the same line)."
  [^String text ^long idx]
  (and (or (zero? idx)
           (let [prev (dec idx)]
             (when (>= prev 0)
               (let [ch (.charAt text prev)]
                 (or (= ch \newline) (= ch \return))))))
       (let [line-end (str/index-of text "\n" idx)]
         (if line-end
           (str/includes? (subs text idx line-end) "@")
           (str/includes? (subs text idx) "@")))))

(defn- find-from-in-buffer
  "Search text for 'From ' at start of line. Returns absolute byte pos or nil."
  [^String text ^long buf-pos]
  (loop [idx (str/index-of text "From ")]
    (when idx
      (if (is-from-start? text idx)
        (+ buf-pos idx)
        (recur (str/index-of text "From " (+ idx 5)))))))

(defn- find-next-from-boundary
  "Returns byte position of the next 'From ' at start of line, or nil."
  [^java.io.RandomAccessFile raf ^long start-pos ^long max-lookahead]
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
```

### Zero-copy chunk writer

```clojure
(defn- copy-range
  [^java.nio.channels.FileChannel src ^java.io.File dest ^long start ^long end]
  (.position src start)
  (let [size (- end start)
        opts (into-array java.nio.file.StandardOpenOption
                         [java.nio.file.StandardOpenOption/CREATE
                          java.nio.file.StandardOpenOption/WRITE
                          java.nio.file.StandardOpenOption/TRUNCATE_EXISTING])]
    (with-open [dst (java.nio.channels.FileChannel/open (.toPath dest) opts)]
      (.transferFrom dst src 0 size))))
```

### Main split loop

```clojure
(defn split-mbox!
  [^String mbox-path ^long chunk-size-mb ^String out-dir]
  (let [chunk-size     (* chunk-size-mb 1024 1024)
        lookahead-bytes (* 8 1024 1024)
        stem (-> (java.io.File. mbox-path) .getName (str/replace #"\.mbox$" ""))]
    (.mkdirs (java.io.File. out-dir))
    (with-open [raf (java.io.RandomAccessFile. mbox-path "r")
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
                  out-file (java.io.File. (format "%s/%s.part-%04d.mbox"
                                                  out-dir stem (inc chunk-idx)))]
              (when (< start-pos end-pos)
                (println (format "  Writing chunk %d (%d MB target):"
                                 (inc chunk-idx) chunk-size-mb))
                (println (format "    %s" (.getName out-file)))
                (copy-range src-channel out-file start-pos end-pos)
                (println (format "    %d bytes" (- end-pos start-pos))))
              (recur (inc chunk-idx) end-pos))))))))
```

## 5. Usage

```bash
# Default 500 MB chunks
takeout split ~/Takeout/Mail/Inbox.mbox

# Custom chunk size
takeout split ~/Takeout/Mail/Inbox.mbox -s 1024

# Output to specific directory
takeout split ~/Takeout/Mail/Inbox.mbox -o ~/Takeout/Mail/split/
```

**Output files**:

- `Inbox.part-0001.mbox`
- `Inbox.part-0002.mbox`
- ...

## 6. Performance & Safety

- **Fast I/O**: NIO `transferFrom` (zero-copy where possible, no byte-by-byte copying).
- **Safe splits**: Both start and end of each chunk are aligned to `From ` at line start.
- **False positive rejection**: Checks for `@` in the `From ` line to avoid splitting in the middle of binary attachments.
- **Low memory**: 16 KB buffer for scanning, no full file loading.
- **Valid output**: Every chunk is a valid mbox file that MboxIterator can consume.
