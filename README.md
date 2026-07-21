# Takeout

**Parse, ingest, query, and enrich Google Takeout MBOX exports.**

Ingests MBOX files into a [Datalevin](https://github.com/juji-io/datalevin) database,
extracts Gmail labels, thread IDs, and message IDs from the `From_` envelope line.
Supports querying by label, subject, sender, date range, and more.

Includes scripts for:
- Mapping RFC822 Message-IDs to Gmail internal IDs via the Gmail API
- Fetching email bodies via `gws gmail +read` with HTML-to-text conversion
- Extracting URLs from email bodies and fetching linked content (arxiv, GitHub, YouTube)
- Storing content as structured entities with host, type, and source tracking
- Discovering top GitHub repositories by topic

## Quick Start

```bash
# Build
clojure -T:build ci

# Ingest an MBOX file
./takeout -d emails.db ingest ~/Takeout/Mail/Sent.mbox

# Query
./takeout -d emails.db query -l "Important" -n 10 --format table

# Show help
./takeout --help
```

## Commands

| Command | Description |
|---------|-------------|
| `ingest <files...>` | Load MBOX files into the database |
| `query` | Search emails by subject, label, sender, date, text |
| `stats` | Database summary statistics |
| `export` | Dump emails as JSON or EDN |
| `threads` | List and explore email threads |
| `split <files...>` | Split large MBOX files into smaller chunks |
| `labels` | List all email labels |
| `addresses` | List all email addresses |
| `frequencies` | Label frequency distribution |
| `mbox-info <files...>` | MBOX file message count and size |
| `propagate` | Propagate a label to all emails in threads |
| `inspect-thread` | Label distribution within a thread |
| `linked-labels` | Labels that co-occur with a given label |
| `update-message-ids` | Add `:email/gmail-id` from Gmail API mapping |
| `update-bodies` | Populate `:email/body-truncated` from fetched bodies |
| `extract-urls` | Extract `https?://` URLs from email bodies by label |
| `update-links` | Store extracted URLs as `:email/links` on emails |
| `upsert-content` | Store fetched content as `:content` entities |
| `content` | Query content entities joined with source emails |

See `doc/cli-query-guide.md` for full documentation.

## Scripts

| Script | Description |
|--------|-------------|
| `scripts/map_gmail_ids.clj` | Map RFC822 Message-IDs → Gmail internal IDs via `gws` API |
| `scripts/fetch-bodies.clj` | Fetch full email bodies via `gws gmail +read` |
| `scripts/fetch-content.clj` | Fetch linked content: arxiv abstracts, GitHub READMEs, YouTube metadata |
| `scripts/update-label-pipeline.clj` | Run all 4 steps (map IDs → update → fetch bodies → update) for a label |
| `scripts/top-repos.clj` | Fetch top GitHub repos by topic, sorted by stars |
| `scripts/ingest-sent-emailsdb.clj` | Ingest all Sent-001 MBOX part files |

### Gmail ID mapping

```bash
./takeout -d emails.db query -l "important" --format edn \
  | bb scripts/map_gmail_ids.clj -o map-ids.edn --no-dry-run

./takeout -d emails.db update-message-ids --from map-ids.edn
```

### Email body fetching

```bash
./takeout -d emails.db query -l "important" --format edn \
  | bb scripts/fetch-bodies.clj -o bodies.edn --no-dry-run

./takeout -d emails.db update-bodies --from bodies.edn
```

### URL extraction and content pipeline

```bash
# 1. Extract URLs from email bodies
./takeout extract-urls -l "trading" -n 100 -o urls.edn
./takeout update-links --from urls.edn

# 2. Fetch linked content (arxiv XML, GitHub READMEs, YouTube metadata)
./takeout query -l "trading" --format edn \
  | bb scripts/fetch-content.clj -o content.edn --no-dry-run

# 3. Store content as entities
./takeout upsert-content --from content.edn

# 4. Query content joined with source emails
./takeout content -l "trading"
./takeout content -h github.com
./takeout content -t paper --format edn
```

### Full pipeline for a label

```bash
bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run
```

### Discover top repos

```bash
bb scripts/top-repos.clj clojure
bb scripts/top-repos.clj machine-learning 20 -o ml-repos.md
```

## Database Schema

### Email entities

| Attribute | Type | Description |
|-----------|------|-------------|
| `:email/id` | string (unique) | RFC822 Message-ID header |
| `:email/gmail-id` | string | Gmail internal message ID (from `From_` line or gws API) |
| `:email/subject` | string (fulltext) | Email subject |
| `:email/from` | string | Sender address |
| `:email/to` | string (many) | Recipients |
| `:email/cc` | string (many) | CC recipients |
| `:email/date` | instant | Sent date |
| `:email/labels` | string (many) | Gmail labels |
| `:email/links` | string (many) | Extracted `https?://` URLs from body |
| `:email/thread-id` | string | Thread ID |
| `:email/body-truncated` | string (fulltext) | Plain text body (first 10K chars) |
| `:email/body-length` | long | Full body character count |
| `:email/html` | string | HTML body (if available) |
| `:email/source` | string | Origin label (e.g. "google-takeout") |
| `:email/mbox-file` | string | Source MBOX filename |
| `:email/attachments` | string (many) | Attachment metadata as EDN |

### Content entities

| Attribute | Type | Description |
|-----------|------|-------------|
| `:content/id` | string (unique) | SHA-256 hash of `:content/url` |
| `:content/url` | string | Source URL |
| `:content/host` | string | Domain (arxiv.org, github.com, youtube.com, etc.) |
| `:content/type` | keyword | `:paper`, `:git-repo`, or `:video-transcript` |
| `:content/body` | string | Raw XML (arxiv), markdown (GitHub), or title+description (YouTube) |
| `:content/source-email` | string | `:email/id` of the email this content was extracted from |

## Requirements

- Clojure 1.12+
- Babashka (for scripts)
- `gws` CLI (for Gmail API scripts) — [googleworkspace/cli](https://github.com/googleworkspace/cli)
- `yt-dlp` (for YouTube metadata in `fetch-content.clj`)
- Java 21+

## Development

```bash
# Run tests
clojure -M:test -m cognitect.test-runner -d test/

# Start REPL
clojure -M:repl/conjure

# Lint
clj-kondo --lint src/
```
