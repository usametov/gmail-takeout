# CLI Query Guide

Query your email database from the command line using `./takeout query`.

## Table of Contents

1. [Basic Usage](#basic-usage)
2. [Filtering](#filtering)
3. [Pagination](#pagination)
4. [Output Formats](#output-formats)
5. [Threads CLI](#threads-cli)
6. [Statistics](#statistics)
7. [CLI vs REPL](#cli-vs-repl)
8. [All Options Reference](#all-options-reference)

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