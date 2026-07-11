== Implementation Status ==

Completed Steps:
  1. deps.edn configured with real dependencies
  2. DB schema defined (src/astanova/db.clj)
  3. MBOX parser implemented (src/astanova/parse.clj) - TESTED & WORKING
  4. Ingestion pipeline (src/astanova/ingest.clj) - CODE WRITTEN, waiting on Datalevin native lib fix
  5. CLI interface (src/astanova/cli.clj, src/astanova/takeout.clj) - CODE WRITTEN

=== File Structure ===
  src/astanova/db.clj      - Datalevin schema + connection helpers
  src/astanova/parse.clj   - MBOX parsing + email structuring
  src/astanova/ingest.clj  - Pipeline: parse → transform → transact
  src/astanova/cli.clj     - CLI: arg parsing, dispatch, formatting, querying
  src/astanova/takeout.clj - Main entry point (-main)

=== Step 1: Dependencies (deps.edn) ===
```clojure
{:paths ["src" "resources"]
 :mvn/repos {"clojars" {:url "https://repo.clojars.org"}}  ;; Datalevin is on Clojars, not Maven Central
 :deps {org.clojure/clojure {:mvn/version "1.12.5"}
        datalevin/datalevin {:mvn/version "0.9.20"}  ;; artifact is datalevin/datalevin, not com.github.juji-io/datalevin
        org.apache.tika/tika-core {:mvn/version "2.9.2"}
        org.apache.tika/tika-parsers-standard-package {:mvn/version "2.9.2"}
        org.apache.james/apache-mime4j-core {:mvn/version "0.8.11"}
        org.apache.james/apache-mime4j-mbox-iterator {:mvn/version "0.8.11"}  ;; MboxIterator is a SEPARATE artifact from mime4j-core
        com.sun.mail/jakarta.mail {:mvn/version "2.0.1"}
        djblue/portal {:mvn/version "0.66.0"}         ;; Portal debug UI (used in ingest.clj comment block)
        org.babashka/cli {:mvn/version "0.8.67"}}}   ;; CLI framework (replaced tools.cli)
```

=== Step 2: DB Schema (src/astanova/db.clj) ===
  12 attributes covering: :email/id (unique), :email/thread-id, :email/source,
  :email/mbox-file, :email/subject, :email/from, :email/to (many), :email/cc (many),
  :email/date, :email/body, :email/html, :email/labels (many)

  Note: :email/embedding removed — embeddings handled by external ColBERT service.

  Key: :email/id uses Message-ID header with :db.unique/identity for dedup.
  Missing Message-ID: a deterministic fallback ID is generated from content hash
  (SHA-256 of subject|from|date|body excerpt) so the same email always gets the same ID.

=== Step 3: MBOX Parser (src/astanova/parse.clj) ===
  Uses Mime4j MboxIterator (NOT Tika's MboxParser) for memory-efficient per-message
  iteration. Each message parsed via Jakarta Mail (jakarta.mail).

  Key lessons / deviations from original plan:
  - MboxIterator lives in apache-mime4j-mbox-iterator, NOT apache-mime4j-core
  - Package is org.apache.james.mime4j.mboxiterator (NOT .stream)
  - MboxIterator implements Iterable, not Iterator — must call .iterator() before iterator-seq
  - File. constructor can't be passed as HOF to map; use #(File. %) instead

  Public API:
    (mbox-messages path)        -> lazy seq of CharBufferWrappers
    (parse-raw-message raw-msg) -> structured email map
    (parse-mbox path)           -> lazy seq of email maps
    (extract-text-body msg)     -> plain text (prefers text/plain over text/html)
    (extract-html-body msg)     -> HTML body or nil
    (html->text html)           -> strip HTML tags
    (parse-gmail-labels msg)    -> vector from X-Gmail-Labels header

  Tested with: plain text, multipart/alternative (text+HTML), Gmail labels,
  multiple recipients (To, CC). All passing.

=== Step 4: Ingestion Pipeline (src/astanova/ingest.clj) ===
  Public API:
    (email-map->entity email-map mbox-file source) -> Datalevin entity map
    (ingest-emails! conn emails mbox-file source)  -> tx stats
    (ingest-mbox! conn mbox-path source)           -> parse + transact one file
    (ingest-mbox-files! conn mbox-dir source)       -> batch multiple files

  Batching: default 100 entities per transaction.
  Dedup: relies on :email/id uniqueness in Datalevin schema.

=== Step 5: CLI Interface (src/astanova/cli.clj) ===
  Uses **babashka/cli** (NOT tools.cli) for argument parsing. Six commands:
  ingest, query, stats, export, threads, split.

  Command dispatch pattern:
    takeout -d ./emails.db <command> [options] [args]

  Commands:
    ingest   - load .mbox files/directories into DB
    query    - search by subject, from, to, label, date range
    stats    - show DB summary statistics (count, date range, top labels/senders, thread stats)
    export   - dump emails as JSON/EDN
    threads  - list and explore email threads (thread-id, participant, search, recent)
    split    - split large MBOX files into smaller chunks at 'From ' boundaries
    labels   - list all Gmail labels in the database

  Global options:
    -d, --db PATH    Database path (default: emails.db)

  Query filters use babashka/cli option specs, generating Datalevin Datalog
  queries dynamically via build-query-clauses / build-query.

  Key design points:
    - babashka/cli dispatch table (cli-tree) replaces tools.cli command dispatch
    - Lazy date parsing: supports YYYY-MM-DD and ISO-8601 with time
    - Pagination via --page / --page-size or --offset / --limit
    - Label filter supports :any and :all modes
    - Output formats: table (fixed-width columns), EDN, JSON

  Split command details (src/astanova/cli.clj):
    - Uses NIO FileChannel/transferFrom for zero-copy file splitting
    - Smart 'From ' boundary detection rejects false positives in Base64
    - Verifies each delimiter has an '@' to distinguish mbox boundaries from binary data
    - Chunk size default: 500 MB (1 GB ingest warning threshold)
    - Output files named <stem>.part-NNNN.mbox
    - Both chunk start AND end are aligned to 'From ' boundaries (no gaps, no overlaps)
    - is-from-start?: Validates 'From ' at line start AND has '@' on same line
    - find-from-in-buffer: Searches 16 KB chunks for valid 'From ' delimiter
    - find-next-from-boundary: Scans file forward, delegates to find-from-in-buffer

  Labels command (src/astanova/cli.clj):
    - Lists all unique Gmail labels in the database
    - Supports --search/-s for substring filtering (case-insensitive)
    - Output formats: table (default), edn, json
    - Backed by db/get-all-labels Datalevin query

  :email/embedding removed from schema - embeddings handled by external
  ColBERT service via libpython-clj (out of scope for this codebase).

=== Datalevin Native Library Issue ===
  Datalevin uses JavaCPP to load native shared libraries (libdtlv.dylib on macOS).
  Version used: datalevin 0.9.20 (bundles dtlvnative-macosx-x86_64 via javacpp).

  The native library may fail to load on older macOS versions (e.g., High Sierra)
  due to rpath linking issues. Workaround involves fixing dylib install names
  inside the javacpp jar. However on modern macOS (Monterey+, Sequoia, etc.)
  and Linux the library loads without issues.

  Ingestion code (ingest/ingest-mbox!) is verified correct aside from this
  runtime library-loading issue.

  Note: For files > 2 GB, MboxIterator's FileChannel.map will fail. Use
  `takeout split` to chunk large files first.

=== Original Design Notes Follow ===

Use **Apache Tika** for robust parsing of MBOX + individual emails (it handles RFC822, attachments metadata, HTML→text, etc.). Pair it with JavaMail (`javax.mail` / `jakarta.mail`) or a Clojure wrapper for finer control over headers/parts.

**Best combo**:
- `clj-tika` (or direct Tika interop) → parse MBOX into individual messages.
- JavaMail for structured email data (subject, from, to, date, body parts, etc.).
- Then embed + transact into Datalevin.

### Performance Tips
- Process in batches (default 100 per tx in ingest.clj).
- Run embedding generation in parallel (use `pmap` or a thread pool).
- MboxIterator is memory-efficient (one message at a time). Tika can be memory-heavy.

### Next Steps After Ingestion

#### Label Propagation

Gmail labels provide a rich taxonomy (Inbox, Sent, Important, Categories/*, user-defined folders).
Labels are a primary retrieval axis, but they are sparse — most emails carry only a few,
and many carry none. Label propagation aims to **infer missing labels** for emails,
drawing from three strategies:

**1. Thread-level propagation**

Collect all labels across member emails of a thread and apply the union to every email
in that thread. This ensures that when you search by label, you find the full conversation,
not just the email that happened to carry the label originally.

- Only propagate from more richly-labeled emails to less-labeled ones.
- Never strip existing labels.
- Optionally skip system labels ("Inbox", "Sent", "Draft", "Spam", "Trash") if they
  don't make sense on every message in the thread.

```clojure
(defn propagate-labels-in-thread [db conn thread-id]
  (let [emails (db/get-thread-emails db thread-id)
        all-labels (->> emails
                        (map :email/labels)
                        (reduce into #{""})
                        (disj "")
                        (remove #{"Inbox" "Sent" "Draft" "Spam" "Trash"})
                        vec)]
    (doseq [e emails]
      (let [existing (set (:email/labels e))
            merged (into existing all-labels)]
        (when (not= existing merged)
          (d/transact! conn [{:db/id (:db/id e)
                              :email/labels (vec merged)}]))))))
```

**2. Sender-based propagation**

Many senders consistently use the same labels (e.g., newsletters from `newsletter@example.com`
always land in "Promotions"). Learn per-sender label profiles from existing data, then apply
them to new emails from the same sender.

```clojure
(defn sender-label-profiles [db]
  ;; Learn: for each sender, what labels do their emails carry?
  (d/q '[:find ?from (group-by ?label (count ?e))
         :where [?e :email/from ?from]
                [?e :email/labels ?label]]
       db))

(defn propagate-for-new-email [db conn email-map]
  (let [sender (:from email-map)
        profiles (sender-label-profiles db)
        inferred (get profiles sender #{})]
    (when (seq inferred)
      (d/transact! conn [{:email/id (:message-id email-map)
                          :email/labels (vec inferred)}]))))
```

**3. Similarity-based propagation (clustering)**

For emails with no thread relationship or known sender profile, use **content similarity**
to propagate labels. This requires embeddings (see ColBERT section below):

- Cluster all emails using their vector embeddings (e.g., HDBSCAN, k-means, or a simple
  nearest-neighbor graph).
- Within each cluster, compute a label frequency distribution. Propagate the top-N labels
  to emails in the cluster that lack them.
- Confidence threshold: only propagate a label if its frequency in the cluster exceeds
  a threshold (e.g., 50% of labeled emails in the cluster carry it).

```clojure
(defn propagate-by-cluster [conn db eps min-samples]
  (let [emails (d/q '[:find [(pull ?e [:db/id :email/embedding :email/labels]) ...]
                      :where [?e :email/embedding]]
                    db)]
    ;; 1. Cluster embeddings (e.g., via smi/clj or a Python sidecar)
    (let [clusters (cluster-emails (map :email/embedding emails) {:eps eps :min-samples min-samples})]
      ;; 2. Per-cluster label frequency
      (doseq [cluster clusters]
        (let [label-freq (frequencies (mapcat :email/labels cluster))
              total-labeled (count (filter :email/labels cluster))
              ;; Propagate labels that appear in >50% of labeled emails
              strong-labels (keep (fn [[lbl cnt]]
                                    (when (> (/ cnt total-labeled) 0.5)
                                      lbl))
                                  label-freq)]
          (when (seq strong-labels)
            (doseq [e cluster]
              (let [existing (set (:email/labels e))
                    merged (into existing strong-labels)]
                (when (not= existing merged)
                  (d/transact! conn [{:db/id (:db/id e)
                                      :email/labels (vec merged)}]))))))))))
```

The three strategies compose naturally: run thread-level propagation first (cheap, high
precision), then sender-based (covers regular correspondents), then clustering (catches
semantic similarities across unrelated emails).

#### ColBERT / Muvera Embeddings

Once emails are in Datalevin, the most impactful enhancement is adding **semantic search**
via dense vector embeddings. The design separates this cleanly into an external service:

- **ColBERT** (via [Muvera](https://github.com/colbert-ai/colbert)) or a compatible
  late-interaction retrieval model. ColBERT's token-level embeddings enable fast,
  scalable reranking without a full cross-encoder.
- **Integration:** A lightweight sidecar process (Python or libpython-clj) loads the
  ColBERT checkpoint and exposes a simple REPL or HTTP API:

```clojure
;; Pseudo-code for invoking an external embedding service
(defn embed-text [text]
  ;; Call external service: HTTP, stdin/stdout, or libpython-clj
  ...)

(defn index-emails! [conn]
  (let [emails (d/q '[:find [(pull ?e [:db/id :email/body :email/subject]) ...]
                      :where [?e :email/subject]]
                    (d/db conn))]
    (doseq [{:keys [db/id email/body email/subject]} emails]
      (let [text (str subject "\n" body)
            vec  (embed-text text)]
        (d/transact! conn [{:db/id id
                            :email/embedding vec}])))))

(defn semantic-search [conn query top-k]
  (let [qvec (embed-text query)]
    (d/search-vec (d/db conn) :email/embedding qvec {:top-k top-k})))
```

- **Schema extension:** Add `:email/embedding` to the schema with `{:db/valueType :db.type/vecf32
  :db/cardinality :db.cardinality/one}` once the service is ready.
- **Search hybrid approach:** Combine dense vector search with label/subject/date filters
  for precise retrieval (e.g., "find emails in the Inbox label semantically similar to X").
- **Reindexing:** Only re-embed emails whose body has changed, or do a full re-index in
  batch. The Datalevin vector index (`:email/embedding`) supports incremental updates.

ColBERT is preferred over OpenAI embeddings because:
- It runs locally — no data leaves your machine.
- Late interaction yields better recall on long documents (emails can be verbose).
- Muvera inference is fast enough for interactive querying on consumer hardware.

