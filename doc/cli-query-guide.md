# CLI Query Guide

Query your email database from the command line using `./takeout query`.

## Table of Contents

1. [Basic Usage](#basic-usage)
2. [Filtering](#filtering)
3. [Pagination](#pagination)
4. [Output Formats](#output-formats)
5. [Threads CLI](#threads-cli)
6. [Addresses Command](#addresses-command)
7. [Frequencies Command](#frequencies-command)
8. [Propagate Command](#propagate-command)
9. [Inspect Thread Command](#inspect-thread-command)
10. [MBOX Info Command](#mbox-info-command)
11. [Split Command](#split-command)
12. [Update Message IDs](#update-message-ids)
13. [Update Bodies](#update-bodies)
14. [Gmail ID Mapping Script](#gmail-id-mapping-script)
15. [Fetch Bodies Script](#fetch-bodies-script)
16. [Pipeline Script](#pipeline-script)
17. [Statistics](#statistics)
18. [Labels Command](#labels-command)
19. [CLI vs REPL](#cli-vs-repl)
20. [All Options Reference](#all-options-reference)

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

### Filter by sender / recipient / address

```bash
# Exact sender match
./takeout -d emails.db query -f "alice@example.com"

# Exact recipient match
./takeout -d emails.db query -t "bob@example.com"

# Match across from, to, or cc
./takeout -d emails.db query -a "alice@example.com"
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

## Addresses Command

List all unique email addresses (from, to, cc) in the database, optionally
filtered by labels.

### List all addresses

```bash
./takeout -d emails.db addresses
```

### Filter by labels (any match)

```bash
# Addresses from emails matching ANY of these labels
./takeout -d emails.db addresses -l "Important,Starred"
./takeout -d emails.db addresses -l "ai/chatbots/watson"
```

### Filter by labels (all must match)

```bash
./takeout -d emails.db addresses -l "education/coursera,IBM" --labels-mode all
```

### Filter by substring

```bash
# Only show addresses containing a string (case-insensitive)
./takeout -d emails.db addresses -s "@gmail.com"
./takeout -d emails.db addresses -l "Important" -s "@company.com"
```

### Output formats

```bash
# Table (default) — sorted columns, one per line
./takeout -d emails.db addresses

# EDN — map with :count and :addresses keys
./takeout -d emails.db addresses --format edn

# JSON — object with count and addresses keys
./takeout -d emails.db addresses --format json
```

### Addresses options reference

| Option | Description |
|--------|-------------|
| `-l` / `--labels` | Comma-separated Gmail labels to filter by |
| `--labels-mode` | `any` or `all` — how to combine labels (default: `any`) |
| `-s` / `--search` | Filter addresses by substring (case-insensitive) |
| `--format` | Output: `table`, `edn`, or `json` (default: `table`) |

---

## Frequencies Command

Show label frequency distribution — every label with its email count, sorted
descending.

```bash
# All labels with counts
./takeout -d emails.db frequencies

# Search for specific labels
./takeout -d emails.db frequencies -s trading

# Limit to top N
./takeout -d emails.db frequencies -n 20

# Output formats
./takeout -d emails.db frequencies --format edn
./takeout -d emails.db frequencies --format json
```

Output:
```
45 labels:
  Label                              Count
  ---------------------------------------------
  Inbox                              18345
  Sent                               12304
  Important                          5623
  trading                            459
  ...
```

### Frequencies options

| Option | Description |
|--------|-------------|
| `-s` / `--search` | Filter labels by substring (case-insensitive) |
| `-n` / `--limit` | Max results (0 = all, default) |
| `--format` | Output: `table`, `edn`, or `json` (default: `table`) |

---

## Propagate Command

Propagate a label to all emails in threads that already contain it. If any
email in a thread has the label, all other emails in that thread receive it.

```bash
# Preview before applying
./takeout -d emails.db propagate -l "trading" --dry-run

# Apply
./takeout -d emails.db propagate -l "trading"
```

Output:
```
Propagated label "trading" to threads:
  247 threads affected
  1835 total emails in those threads
  412 emails updated
```

### Propagate options

| Option | Description |
|--------|-------------|
| `-l` / `--label` | Label to propagate (required) |
| `--dry-run` | Preview only, don't transact |
| `--format` | Output: `table`, `edn`, or `json` (default: `table`) |

---

## Inspect Thread Command

Inspect label distribution within a specific thread. Shows every email with
its labels, and highlights which labels are common vs sparse.

```bash
# Find a thread-id first
./takeout -d emails.db query -l "trading" -n 1 --format edn
# => {:results ({:email/thread-id "abc123" ...})}

# Full thread view
./takeout -d emails.db inspect-thread -t "abc123"

# Check coverage for a specific label
./takeout -d emails.db inspect-thread -t "abc123" -l "trading"
```

Output:
```
Thread: abc123
Emails: 5
All labels: Inbox, Sent, trading
Common labels: Inbox
Sparse labels: Sent, trading

  2024-01-01 | alice@example.com
  Subject: Research update
  labels: Inbox

  2024-01-02 | bob@test.com
  Subject: Re: Research update
  labels: Inbox, Sent, trading
```

### Inspect Thread options

| Option | Description |
|--------|-------------|
| `-t` / `--thread-id` | Thread ID to inspect (required) |
| `-l` / `--label` | Check coverage for a specific label |
| `--format` | Output: `table`, `edn`, or `json` (default: `table`) |

---

## MBOX Info Command

Show diagnostic information about an MBOX file: message count and size.

```bash
# Single file
./takeout mbox-info ~/Takeout/Mail/Inbox.mbox

# Multiple files
./takeout mbox-info ~/Takeout/Mail/*.mbox

# EDN output for scripting
./takeout mbox-info ~/Takeout/Mail/Inbox.mbox --format edn
```

Output:
```
Inbox.mbox: 3823 messages (504 MB)
```

Useful for comparing expected message counts against actual ingested counts.

### MBOX Info options

| Option | Description |
|--------|-------------|
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

## Update Message IDs

Add Gmail internal IDs (`:email/gmail-id`) and fix thread IDs (`:email/thread-id`)
in the database using a mapping file produced by `scripts/map_gmail_ids.bb`.

This command does **not** change `:email/id` — it only adds the Gmail internal ID
and corrects the thread ID obtained from the Gmail API.

### Prerequisites

First, generate a mapping file using `scripts/map_gmail_ids.bb` (see next section).
The mapping file is an EDN map:

```edn
{"<CAE=9OB...@mail.gmail.com>" {:gmail-id "188a1b2c3d4e5f6"
                                :thread-id "188a1b2c3d4e5f7"}
 "<other@mail.gmail.com>"       {:error "not-found"}
 ...}
```

### Usage

```bash
# Preview changes
./takeout -d emails.db update-message-ids --from map-ids.edn --dry-run

# Apply
./takeout -d emails.db update-message-ids --from map-ids.edn
```

Output:

```
Processing 45 entries from map-ids.edn...
  <CAE=9OB...@mail.gmail.com>        gmail-id=188a1b2c3d4e5f6 thread=188a1b2c3d4e5f7
  SKIP: <other@mail.gmail.com> (error: not-found)
  NOT FOUND: <missing@mail.gmail.com>

Done: 42 updated, 2 skipped (errors), 1 not found, 45 total
```

### Options

| Option | Description |
|--------|-------------|
| `-f` / `--from` | EDN mapping file from `map_gmail_ids.clj` (required) |
| `--dry-run` | Preview updates without transacting |
| `--force` | Update even if `:email/gmail-id` already set |

---

## Update Bodies

Update `:email/body-truncated` and `:email/body-length` in the database
using a bodies EDN file produced by `scripts/fetch-bodies.clj`.

HTML bodies are converted to plain text using Hickory. Emails that already
have a non-empty body are left untouched.

### Prerequisites

First, generate a bodies file using `scripts/fetch-bodies.clj` (see next section).
The bodies file is an EDN map:

```edn
{"<msg-id>" {:body "<html>...</html>" :gmail-id "18a1abc" :subject "..."}
 "<msg-id2>" {:error "fetch-failed" :gmail-id "18b2def"}
 ...}
```

### Usage

```bash
# Preview changes
./takeout -d emails.db update-bodies --from bodies.edn --dry-run

# Apply
./takeout -d emails.db update-bodies --from bodies.edn
```

Output:

```
Processing 45 entries from bodies.edn...
  <msg-id>                                           body=1523 chars
  SKIP (no body): gmail-id=18b2def msg-id=<msg-id2> error=fetch-failed
  NOT FOUND: <missing-id>

Done: 42 updated, 1 skipped (no body), 1 not found, 45 total
```

### Options

| Option | Description |
|--------|-------------|
| `-f` / `--from` | EDN bodies file from `fetch-bodies.clj` (required) |
| `--dry-run` | Preview updates without transacting |

---

## Gmail ID Mapping Script

`scripts/map_gmail_ids.clj` is a Babashka script that calls the Gmail API
(via the `gws` CLI) to look up Gmail internal message IDs and thread IDs
from RFC822 `Message-ID` headers. Skips emails that already have
`:email/gmail-id` set (use `--force` to override).

### Workflow

```bash
# Step 1: Query emails and pipe to the mapping script (dry-run first)
./takeout -d emails.db query -l "trading" -n 100 --format edn \
  | bb scripts/map_gmail_ids.clj -o map-ids.edn

# Step 2: Verify map-ids.edn looks correct, then run real API calls
./takeout -d emails.db query -l "trading" -n 100 --format edn \
  | bb scripts/map_gmail_ids.clj -o map-ids.edn --no-dry-run

# Step 3: Update the database
./takeout -d emails.db update-message-ids --from map-ids.edn --dry-run
./takeout -d emails.db update-message-ids --from map-ids.edn

# Force re-fetch all IDs (ignore existing :email/gmail-id):
./takeout -d emails.db query -l "trading" --format edn \
  | bb scripts/map_gmail_ids.clj -o map-ids.edn --no-dry-run --force
```

### How it works

1. Reads EDN from stdin — accepts the query output format `{:total .. :results [...]}`
   or raw lists of `:email/id` values
2. Skips entries that already have `:email/gmail-id` (unless `--force`)
3. For each `:email/id` (RFC822 Message-ID), calls:
   ```bash
   gws gmail users messages list --params '{"userId":"me","q":"rfc822msgid:<id>"}'
   ```
4. Extracts Gmail internal `id` and `threadId` from the JSON response
5. Writes the mapping as EDN to the output file

### Options

| Option | Description |
|--------|-------------|
| `-o` / `--out-file` | Output EDN file (default: `gmail-ids.edn`) |
| `-d` / `--delay` | Delay in ms between API calls (default: `1000`) |
| `-n` / `--limit` | Max IDs to process (0 = all, default) |
| `-s` / `--skip` | Skip first N IDs (for resume) |
| `--user-id` | Gmail user ID (default: `me`) |
| `--no-dry-run` | Actually call the Gmail API |
| `--force` | Re-fetch even if `:email/gmail-id` already set |

### Output format

```edn
{"<CAE=9OB...@mail.gmail.com>" {:gmail-id "188a1b2c3d4e5f6"
                                :thread-id "188a1b2c3d4e5f7"}
 "<not-in-gmail@example.com>"  {:error "not-found"}}
```

Entries with `:error` are skipped by `update-message-ids`.

---

## Fetch Bodies Script

`scripts/fetch-bodies.clj` fetches full email bodies from the Gmail API
for emails with empty `:email/body-truncated`. Calls `gws gmail +read`
for each email that has `:email/gmail-id` set.

### Workflow

```bash
# Dry-run first
./takeout -d emails.db query -l "trading" --format edn \
  | bb scripts/fetch-bodies.clj -o bodies.edn

# Real API calls
./takeout -d emails.db query -l "trading" --format edn \
  | bb scripts/fetch-bodies.clj -o bodies.edn --no-dry-run

# Then update the database
./takeout -d emails.db update-bodies --from bodies.edn --dry-run
./takeout -d emails.db update-bodies --from bodies.edn
```

### How it works

1. Reads EDN from stdin — accepts query output format
2. Skips emails with non-empty `:email/body-truncated`
3. Warns about emails missing `:email/gmail-id`
4. For each, calls:
   ```bash
   gws gmail +read --id <gmail-id> --headers --format json
   ```
5. Extracts `body_html` from the JSON response
6. Writes bodies as EDN to the output file

### Options

| Option | Description |
|--------|-------------|
| `-o` / `--out-file` | Output EDN file (default: `bodies.edn`) |
| `-d` / `--delay` | Delay in ms between API calls (default: `500`) |
| `-n` / `--limit` | Max emails to fetch (0 = all, default) |
| `--no-dry-run` | Actually call the Gmail API |

### Output format

```edn
{"<msg-id>" {:body "<html>full email body...</html>"
             :gmail-id "18a1abc"
             :db-id "<CAE=9OB...@mail.gmail.com>"
             :subject "Re: Trading update"
             :from "alice@example.com"}
 "<msg-id2>" {:error "fetch-failed"
              :gmail-id "18b2def"
              :db-id "<other@mail.gmail.com>"}}
```

Entries with `:error` are skipped by `update-bodies`.

---

## Pipeline Script

`scripts/update-label-pipeline.clj` runs all four steps in sequence for a
given label:

```bash
# Dry-run (safe — no API calls, no DB changes)
bb scripts/update-label-pipeline.clj "video/youtube"

# Real run
bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run

# Real run with force, limited to 500 emails
bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run --force -n 500
```

### Steps

1. **Map Gmail IDs** — query emails by label, pipe to `map_gmail_ids.clj`
2. **Update Gmail IDs** — `update-message-ids --from <map-file>`
3. **Fetch bodies** — query again, pipe to `fetch-bodies.clj`
4. **Update bodies** — `update-bodies --from <bodies-file>`

### Options

| Option | Description |
|--------|-------------|
| `--no-dry-run` | Actually call gws API and transact to DB |
| `--force` | Re-fetch Gmail IDs even if already set |
| `-n` / `--limit` | Max emails to process (default: all) |

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

### Addresses options

| Option | Description |
|--------|-------------|
| `-l` / `--labels` | Comma-separated Gmail labels to filter by |
| `--labels-mode` | `any` or `all` — how to combine labels (default: `any`) |
| `-s` / `--search` | Filter addresses by substring (case-insensitive) |
| `--format` | Output: `table`, `edn`, or `json` (default: `table`) |

### Split options

| Option | Description |
|--------|-------------|
| `-s` / `--size` | Approximate chunk size in MB (default: `500`) |
| `-o` / `--output` | Output directory (default: same dir as input) |

### Update Message IDs options

| Option | Description |
|--------|-------------|
| `-f` / `--from` | EDN mapping file from `map_gmail_ids.clj` (required) |
| `--dry-run` | Preview updates without transacting |
| `--force` | Update even if already set |

### Update Bodies options

| Option | Description |
|--------|-------------|
| `-f` / `--from` | EDN bodies file from `fetch-bodies.clj` (required) |
| `--dry-run` | Preview updates without transacting |

### Query options

| Option | Description |
|--------|-------------|
| `-s` / `--subject` | Substring search in subject (case-insensitive) |
| `-f` / `--from` | Exact sender match |
| `-t` / `--to` | Exact recipient match |
| `-a` / `--address` | Match in from, to, or cc |
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