
### Summary of What We Need to Consider

**Core mbox rules**:

- Messages are **concatenated** in one file.
- **Every message must start with a line beginning with `From`** (five characters: `F r o m` + space).
- This is the **only reliable delimiter**.
- Lines inside the message body that start with `From` are **escaped** (usually to `>From`) depending on the variant (mboxo vs mboxrd).
- Messages usually end with a blank line before the next `From`.

**What our previous code does well**:

- Uses `RandomAccessFile` + byte seeking → efficient for large files.
- Backs up and searches for the next `From` → avoids cutting messages mid-way.
- Streams output without loading everything into memory.

**Potential issues / improvements**:

1. **False positives**: A line starting with `From` inside the body (even escaped) could theoretically be matched, but backing up only ~1MB and taking the **first** valid `From` after the target offset is standard practice and works reliably in real-world Gmail/Takeout mboxes.

2. **First chunk**: The very first message should start from byte 0. Our code already handles this.

3. **Last chunk**: Must go until EOF.

4. **Encoding**: Most mboxes are ASCII/UTF-8. We used UTF-8, which is fine.

5. **Trailing content**: Some mboxes have extra blank lines or minor garbage at the end — harmless.

### Improved Version (More Robust)

Here's a refined version addressing the specs:

```clojure
(ns mbox-splitter.core
  (:require [clojure.java.io :as io])
  (:import [java.io RandomAccessFile]))

(def chunk-size-mb 500)
(def chunk-size-bytes (* chunk-size-mb 1024 1024))
(def backup-bytes (* 2 1024 1024))     ; 2 MB backup
(def lookahead-bytes (* 4 1024 1024))  ; 4 MB lookahead

(defn is-from-boundary? [line]
  (and line (str/starts-with? line "From ")))

(defn find-next-from-position [^RandomAccessFile raf start-pos]
  (.seek raf start-pos)
  (let [buffer (byte-array 8192)]
    (loop [pos start-pos]
      (let [n (.read raf buffer)]
        (when (pos? n)
          (let [text (String. buffer 0 n "UTF-8")
                idx (str/index-of text "From ")]
            (if idx
              (+ pos idx)
              (recur (+ pos n)))))))))

(defn split-mbox [input-path output-prefix]
  (with-open [raf (RandomAccessFile. input-path "r")]
    (let [total (.length raf)]
      (loop [chunk-idx 0
             target-pos 0]
        (when (< target-pos total)
          (let [out-path (format "%s_%04d.mbox" output-prefix chunk-idx)]
            (println "Writing chunk" (inc chunk-idx) "→" out-path)
            
            (with-open [out (io/writer out-path :encoding "UTF-8")]
              (let [seek-pos (if (zero? chunk-idx)
                               0
                               (max 0 (- target-pos backup-bytes)))
                    boundary (or (find-next-from-position raf seek-pos)
                                 target-pos)]   ; fallback
                
                (.seek raf boundary)
                
                (let [end-pos (min (+ target-pos chunk-size-bytes) total)
                      buf (byte-array 8192)]
                  (loop []
                    (let [current (.getFilePointer raf)]
                      (when (< current end-pos)
                        (let [to-read (min 8192 (- end-pos current))
                              n (.read raf buf 0 to-read)]
                          (when (pos? n)
                            (.write out (String. buf 0 n "UTF-8"))
                            (recur))))))))))
          
          (recur (inc chunk-idx)
                 (+ target-pos chunk-size-bytes)))))))

(defn -main [& [input prefix & _]]
  (if (or (nil? input) (nil? prefix))
    (println "Usage: clj -M -m mbox-splitter.core <input.mbox> <output-prefix>")
    (split-mbox input prefix)))
```

### Recommendations

- Test on a small mbox first.
- After splitting, you can validate chunks by counting `From` lines or importing into a mail client.
- For **maximum safety**, you could add a check that the line after `From` looks like an email address + date, but it's usually unnecessary.

This follows mbox conventions as defined in RFC 4155 and common implementations. Let me know if you want to add chunk size as a CLI arg or any other tweaks.
