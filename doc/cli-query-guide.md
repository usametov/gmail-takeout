# CLI Query Guide

Query your email database from the command line using `./takeout query`.

## Table of Contents

1. [Basic Usage](#basic-usage)
2. [Filtering](#filtering)
3. [Pagination](#pagination)
4. [Output Formats](#output-formats)
5. [Threads CLI](#threads-cli)
6. [Split Command](#split-command)
7. [Statistics](#statistics)
8. [CLI vs REPL](#cli-vs-repl)
9. [All Options Reference](#all-options-reference)

---

## Basic Usage

```bash
# Default: EDN output, first 20 results
./takeout -d emails.db query

# Filter by subject
./takeout -d emails.db query -s "meeting"

# Filter by sender
./takeout -d emails.db query -f "alice@example.com"
```

### Search by subject

```bash
# Substring search (case-insensitive)
./takeout -d emails.db query -s "invoice"
./takeout -d emails.db query -s "machine learning"
```

### Filter by sender / recipient

```bash
# Exact sender match
./takeout -d emails.db query -f "alice@example.com"

# Exact recipient match
./takeout -d emails.db query -t "bob@example.com"
```

### Filter by date

```bash
# On or after a date
./takeout -d emails.db query --since 2024-01-01

# Before a date
./takeout -d emails.db query --before 2024-06-01

# Date range
./takeout -d emails.db query --since 2024-01-01 --before 2024-12-31
```

---

## Labels

### Single label

```bash
./takeout -d emails.db query -l "Important"
./takeout -d emails.db query -l "ai/chatbots/watson"
```

### Multiple labels (ANY match — default)

```bash
./takeout -d emails.db query -l "Important,Starred"
```

### Multiple labels (ALL must match)

```bash
./takeout -d emails.db query -l "education/coursera,IBM" --labels-mode all
```

### Labels combined with text search

```bash
./takeout -d emails.db query -l "ai/chatbots/watson" --text "machine learning"
./takeout -d emails.db query -l "Important,AI" --text "project update"
```

---

## Pagination

By default, the query returns the first 20 results. Use the pagination options
to control which slice of results you see.

### Using `--limit` / `--offset`

```bash
# First 10 results
./takeout -d emails.db query -l "Important" -n 10

# Next 10 results (offset 10)
./takeout -d emails.db query -l "Important" -n 10 --offset 10

# Page 3 (results 40–59)
./takeout -d emails.db query -l "Important" -n 20 --offset 40
```

### Using `--page` / `--page-size` (1-indexed)

```bash
# Page 1, 10 per page (same as -n 10)
./takeout -d emails.db query -l "Important" --page-size 10

# Page 2
./takeout -d emails.db query -l "Important" --page-size 10 --page 2

# Page 5, 5 per page
./takeout -d emails.db query -l "Important" --page-size 5 --page 5
```

### Pagination in EDN output

With `--format edn` (the default), each query returns a map with pagination metadata:

```edn
{:total 47           ;; total matching results
 :offset 10          ;; current offset
 :limit 10           ;; results per page
 :results [...]}     ;; the page of results
```

This lets you programmatically paginate:

```bash
# Get total count
./takeout -d emails.db query -l "Important" --page-size 1 | head -1

# Iterate through all pages
for page in 1 2 3 4 5; do
  ./takeout -d emails.db query -l "Important" --page-size 10 --page $page > page-$page.edn
done
```

### Pagination with table output

```bash
# Table format also respects --limit / --offset / --page
./takeout -d emails.db query -l "Important" --format table -n 5
./takeout -d emails.db query -l "Important" --format table --page-size 5 --page 2
```

---

## Output Formats

### EDN (default)

```bash
./takeout -d emails.db query -s "meeting"
# => {:total 3, :offset 0, :limit 20, :results (#:email{:subject ...} ...)}
```

EDN is the default format. It includes pagination metadata and is consumable
by Clojure programs.

### Table

```bash
./takeout -d emails.db query -s "meeting" --format table
```

Human-readable table with columns: subject, from, date, labels.

### JSON

```bash
./takeout -d emails.db query -s "meeting" --format json
```

JSON array of results. Note: JSON output does not include pagination metadata.

### Piping to data processors

```bash
# Pipe EDN to a Clojure script
./takeout -d emails.db query -l "Important" | clojure -M -e "(println (count (:results (read-string (slurp *in*)))))"

# Pipe JSON to jq
./takeout -d emails.db query -l "Important" --format json | jq '. | length'
```

---

## Threads CLI

### List recent threads

```bash
./takeout -d emails.db threads
```

### Show specific thread

```bash
./takeout -d emails.db threads -t "thread-id-here"
```

### Find threads by participant

```bash
./takeout -d emails.db threads -p "alice@example.com"
```

### Search threads by subject

```bash
./takeout -d emails.db threads -s "meeting"
```

### Thread CLI options

| Option | Description |
|--------|-------------|
| `-t` / `--thread-id` | Show specific thread |
| `-p` / `--participant` | Find threads by participant |
| `-s` / `--search` | Search threads by subject |
| `-n` / `--limit` | Max results (default: 20) |
| `--format` | Output: `table`, `edn`, or `json` (default: `table`) |

---

## Split Command

Split large MBOX files into smaller chunks before ingesting them.

Google Takeout exports can produce MBOX files exceeding 2 GB, which is the
practical limit for Mime4j's `MboxIterator` (uses memory-mapped file I/O).
The `split` command breaks large files into chunks of roughly the same byte
size, aligned to message boundaries (`From ` delimiters), so each chunk is
independently valid and ingestible.

### Basic usage

```bash
# Split a single file (default: 500 MB chunks)
./takeout split ~/Takeout/Mail/Inbox.mbox

# Specify chunk size (200 MB)
./takeout split ~/Takeout/Mail/Inbox.mbox -s 200

# Specify output directory
./takeout split ~/Takeout/Mail/Inbox.mbox -o /tmp/chunks
```

💡 The splitter works at the byte level using `RandomAccessFile` — it does
not decode or re-encode the file, preserving the original mboxrd format
exactly. Each chunk is a valid mbox file that can be ingested directly.

### Split multiple files or directories

```bash
# Split all .mbox files in a directory
./takeout split ~/Takeout/Mail/

# Split specific files
./takeout split file1.mbox file2.mbox

# Split a directory into 200 MB chunks in a custom output dir
./takeout split ~/Takeout/Mail/ -s 200 -o /tmp/chunks
```

### How it works

1. The splitter reads the file using `RandomAccessFile` (no memory-mapping).
2. Starting from position 0, it copies raw bytes to the first chunk file.
3. After ~500 MB (or the size you specify), it scans forward for the next
   `From ` that starts at the beginning of a line — this is the message
   boundary per RFC 4155.
4. The chunk ends right before that boundary, and the next chunk starts
   there. This guarantees every message is intact in one chunk.
5. The last chunk extends to EOF.

Output files are named `<stem>.part-0001.mbox`, `<stem>.part-0002.mbox`, etc.

### Chunk size guidance

| Chunk size | Use case |
|------------|----------|
| **500 MB** | Default — safe for MboxIterator's ~2 GB limit with headroom |
| **200 MB** | Conservative — faster per-chunk ingestion, more chunks |
| **1000 MB** | Aggressive — fewer chunks, but may approach MboxIterator limits |

The default of 500 MB works well for most Takeout exports. Each chunk is well
under Mime4j's 2 GB limit and ingests quickly.

### Verifying chunks

```bash
# Count messages in each chunk (each 'From ' at line start = one message)
grep -c '^From ' output.part-0001.mbox
grep -c '^From ' output.part-0002.mbox

# Ingest one chunk to test
./takeout -d test.db ingest output.part-0001.mbox
```

### Split options reference

| Option | Description |
|--------|-------------|
| `-s` / `--size` | Approximate chunk size in MB (default: `500`) |
| `-o` / `--output` | Output directory (default: same directory as input) |
| `<file>...` | One or more MBOX files or directories to split |

---

## Statistics

```bash
# Show DB summary statistics
./takeout -d emails.db stats
```

Displays:
- Total email count
- Date range
- Top 10 labels
- Top 10 senders
- Thread statistics (total, average emails per thread, longest threads)

---

## CLI vs REPL

The CLI supports the most common query patterns: subject, sender, recipient,
labels (any/all), text search, date range, and pagination.

For **more complex queries** (analytics, thread analysis, custom Datalog,
multi-label queries with `query-by-labels-*`, combined label+text queries),
use the REPL:

```clojure
(require '[astanova.db :as db])

;; Full programmatic access
(db/top-senders db 20)
(db/query-by-labels-all db ["ai/chatbots/watson" "education/coursera"])
(db/query-by-labels-and-text db ["education/coursera"] "IBM")
```

To start a REPL:

```bash
clojure -M:repl/conjure
```

See the [query-guide.md](query-guide.md) for the complete REPL query reference.

---

## All Options Reference

### Global options

| Option | Description |
|--------|-------------|
| `-d` / `--db` | Datalevin database path (default: `emails.db`) |

### Labels options

| Option | Description |
|--------|-------------|
| `-s` / `--search` | Filter labels by substring (case-insensitive) |
| `--format` | Output: `table`, `edn`, or `json` (default: `table`) |

### Split options

| Option | Description |
|--------|-------------|
| `-s` / `--size` | Approximate chunk size in MB (default: `500`) |
| `-o` / `--output` | Output directory (default: same dir as input) |

### Query options

| Option | Description |
|--------|-------------|
| `-s` / `--subject` | Substring search in subject (case-insensitive) |
| `-f` / `--from` | Exact sender match |
| `-t` / `--to` | Exact recipient match |
| `-l` / `--labels` | Comma-separated Gmail labels |
| `--labels-mode` | `any` or `all` — how to combine labels (default: `any`) |
| `--text` | Search text in subject and body (combines with labels) |
| `--since` | Emails on or after date (e.g. `2024-01-01`) |
| `--before` | Emails before date |
| `-n` / `--limit` | Max results (default: 20) |
| `--offset` | Pagination offset (0-indexed) |
| `--page` | Page number (1-indexed, overrides `--offset`) |
| `--page-size` | Results per page (overrides `--limit`) |
| `--format` | Output: `table`, `edn`, or `json` (default: `edn`) |