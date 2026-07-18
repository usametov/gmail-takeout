# Takeout

**Parse, ingest, query, and enrich Google Takeout MBOX exports.**

Ingests MBOX files into a [Datalevin](https://github.com/juji-io/datalevin) database,
extracts Gmail labels, thread IDs, and message IDs from the `From_` envelope line.
Supports querying by label, subject, sender, date range, and more.
Includes scripts for mapping RFC822 Message-IDs to Gmail internal IDs and fetching
email bodies via the Gmail API.

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

See `doc/cli-query-guide.md` for full documentation.

## Scripts

| Script | Description |
|--------|-------------|
| `scripts/map_gmail_ids.clj` | Map RFC822 Message-IDs → Gmail internal IDs via `gws` API |
| `scripts/fetch-bodies.clj` | Fetch full email bodies via `gws gmail +read` |
| `scripts/update-label-pipeline.clj` | Run all 4 steps for a label |
| `scripts/ingest-sent-emailsdb.clj` | Ingest all Sent-001 MBOX part files |

### Mapping Gmail IDs

```bash
# Dry-run (no API calls)
./takeout -d emails.db query -l "important" --format edn \
  | bb scripts/map_gmail_ids.clj -o map-ids.edn

# Real API calls
./takeout -d emails.db query -l "important" --format edn \
  | bb scripts/map_gmail_ids.clj -o map-ids.edn --no-dry-run

# Store in database
./takeout -d emails.db update-message-ids --from map-ids.edn
```

### Fetching email bodies

```bash
./takeout -d emails.db query -l "important" --format edn \
  | bb scripts/fetch-bodies.clj -o bodies.edn --no-dry-run

./takeout -d emails.db update-bodies --from bodies.edn
```

### Full pipeline

```bash
bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run
```

## Database Schema

Emails are stored as Datalevin entities with the following attributes:

| Attribute | Type | Description |
|-----------|------|-------------|
| `:email/id` | string (unique) | RFC822 Message-ID header |
| `:email/gmail-id` | string | Gmail internal message ID |
| `:email/subject` | string (fulltext) | Email subject |
| `:email/from` | string | Sender address |
| `:email/to` | string (many) | Recipients |
| `:email/cc` | string (many) | CC recipients |
| `:email/date` | instant | Sent date |
| `:email/labels` | string (many) | Gmail labels |
| `:email/thread-id` | string | Thread ID |
| `:email/body-truncated` | string (fulltext) | Plain text body (first 10K chars) |
| `:email/body-length` | long | Full body character count |
| `:email/html` | string | HTML body (if available) |
| `:email/source` | string | Origin label (e.g. "google-takeout") |
| `:email/mbox-file` | string | Source MBOX filename |
| `:email/attachments` | string (many) | Attachment metadata as EDN |

## Requirements

- Clojure 1.12+
- Babashka (for scripts)
- `gws` CLI (for Gmail API scripts) — [googleworkspace/cli](https://github.com/googleworkspace/cli)
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
