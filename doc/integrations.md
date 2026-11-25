# Integrations

## NotebookLM Integration

[NotebookLM](https://notebooklm.google.com/) is Google's AI-powered research
assistant that lets you upload documents and ask questions about their
contents.  Because `astanova/takeout` can query your email archive and
export results as structured Markdown, it pairs naturally with NotebookLM
for deep, contextual analysis of your email history.

### Workflow Overview

```
┌───────────┐     ┌──────────────┐     ┌───────────────┐     ┌────────────┐
│ takeout   │────▶│ scripts/     │────▶│ .md report    │────▶│ NotebookLM │
│ query     │     │ report.clj   │     │ file           │     │ import     │
└───────────┘     └──────────────┘     └───────────────┘     └────────────┘
```

### Step 1: Craft a Focused Query

NotebookLM works best with a targeted set of emails. Run a query that returns a
manageable number of results (ideally up to 50, though larger sets also work)
and pipe the output as EDN:

```bash
# Search by label
./takeout -d emails.db query -l "trading" -n 50 --format edn > /tmp/query.edn

# Search by subject keyword
./takeout -d emails.db query -s "quarterly review" -n 30 --format edn > /tmp/query.edn

# Search by sender
./takeout -d emails.db query -f "boss@company.com" -n 50 --format edn > /tmp/query.edn

# Combine label and text search
./takeout -d emails.db query -l "Important" -s "budget" -n 50 --format edn > /tmp/query.edn
```

**Tip:** The `--format edn` flag is required — `scripts/report.clj` reads EDN
from stdin, not the default table format.

### Step 2: Generate the Markdown Report

Pipe the query output through `scripts/report.clj` to produce a rich Markdown
file ready for NotebookLM import:

```bash
# Pipe directly
./takeout -d emails.db query -l "trading" -n 50 --format edn \
  | bb scripts/report.clj -o trading-report.md

# Or from a saved EDN file
cat /tmp/query.edn | bb scripts/report.clj -o trading-report.md

# Limit the output to fewer emails (e.g., top 20 of a larger query)
./takeout -d emails.db query -l "trading" -n 200 --format edn \
  | bb scripts/report.clj -n 20 -o top20.md
```

**Options for `scripts/report.clj`:**

| Option | Description | Default |
|--------|-------------|---------|
| `-o, --output PATH` | Write to file instead of stdout | stdout |
| `-n, --limit N` | Max emails to include | all from input |

### Step 3: Import into NotebookLM

1. Open [NotebookLM](https://notebooklm.google.com/)
2. Create a new notebook or open an existing one
3. Click **Add source** → **Upload files** (or drag-and-drop)
4. Select the generated `.md` file
5. NotebookLM processes the document and you can start asking questions

### What the Report Contains

`scripts/report.clj` generates a Markdown file with one section per email:

- **Subject** as a level-3 heading
- Metadata table: **From**, **Date**, **Gmail ID**, **Labels**
- **Links** extracted from the email body (up to 50, with GitHub/arXiv links
  filtered out to reduce noise, and a count of omitted links shown)
- **Body preview** — first 500 characters of the email body
- Separator (`---`) between emails

Example output structure:

```markdown
# Email Report

### Quarterly Trading Update
| | |
|---|---|
| **From** | analyst@firm.com |
| **Date** | 2024-03-15 |
| **Gmail ID** | `18a3f4c5d6e7f8g9` |
| **Labels** | `Important` `trading` |
| **Links** |
| | https://example.com/report.pdf |
| | https://example.com/data.csv |

Lorem ipsum dolor sit amet, consectetur adipiscing elit...

---

3 emails*
```

### Use Cases for NotebookLM

- **Summarize long email threads** — export all emails in a thread and ask
  NotebookLM for a concise summary
- **Extract action items** — query emails from a specific project or person and
  ask "What are the unresolved action items?"
- **Timeline reconstruction** — query by date range and ask NotebookLM to build
  a chronological narrative
- **Trend analysis** — export newsletters or reports over time and ask about
  recurring themes
- **Contract/legal review** — export emails about a specific deal and ask about
  key terms, deadlines, or risks
- **Meeting prep** — export recent emails from a contact and ask for a briefing
  before a meeting

### Tips for Best Results

1. **Keep queries focused.**  NotebookLM has a token limit per source.  Stick
   to 30–50 emails per report for best results.  If you need more, split into
   multiple reports by time range or sub-topic.
2. **Use meaningful labels** in your Takeout import so you can query by label
   rather than broad text search.
3. **Include body text.**  The report includes the first 500 characters of
   each email body — enough for NotebookLM to understand context.  If emails
   have empty bodies (common with Takeout exports that only include headers),
   use [`gws` body enrichment](#gws-and-live-gmail-enrichment) to backfill
   full bodies first.
4. **Regenerate as needed.**  NotebookLM sources are static; re-export and
   re-upload when you want fresh data.

---

## gws and live Gmail enrichment

`gws` ([googleworkspace/cli](https://github.com/googleworkspace/cli)) is a
standalone Google Workspace CLI. It is useful for enriching the email database
with live Gmail data independently of any coding agent. See
[Setup](setup.md#gws-google-workspace-cli) for installation and authentication.

### What gws Provides

The `gws` skill exposes Gmail API operations through a CLI:

| Operation | Command | Purpose |
|-----------|---------|---------|
| **List messages** | `gws gmail users messages list` | Search/find message IDs by query |
| **Read message** | `gws gmail +read --id <gmail-id>` | Fetch full email content (headers, body, attachments) |
| **Get attachment** | `gws gmail users messages attachments get` | Download specific attachment data |

These operations enable a pipeline that bridges the gap between static Takeout
MBOX archives and live Gmail data.

### Why You Need gws

Google Takeout MBOX exports have two major limitations:

1. **Missing bodies for some emails.**  Certain Takeout exports (especially
   partial or label-based exports) may only include headers — subject, from,
   date, labels — but **omit the email body**.  The `:email/body-truncated`
   field will be nil or empty.
2. **No attachment content.**  Takeout strips binary attachment data.
   Attachment metadata (filename, content-type, size) is captured during
   ingestion, but the actual file bytes are not included.

The `gws` skill fills these gaps by fetching the missing content live from
Gmail via the API.

### The Full Pipeline

The project includes a complete 4-step pipeline (`scripts/update-label-pipeline.clj`)
that handles everything for a given label:

```
┌───────────────┐     ┌──────────────┐     ┌───────────────┐     ┌──────────────┐
│ 1. Map IDs    │────▶│ 2. Update DB │────▶│ 3. Fetch      │────▶│ 4. Store     │
│ msg-id→gmail  │     │ with gmail   │     │ full bodies   │     │ bodies in    │
│ via gws       │     │ IDs          │     │ via gws       │     │ DB           │
└───────────────┘     └──────────────┘     └───────────────┘     └──────────────┘
```

#### Running the Pipeline

```bash
# Dry-run (see what would happen without making API calls)
bb scripts/update-label-pipeline.clj "video/youtube"

# Live run — fetches IDs, updates DB, fetches bodies, stores bodies
bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run

# With a limit (process only 500 emails)
bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run -n 500

# Force re-fetch even for emails that already have Gmail IDs
bb scripts/update-label-pipeline.clj "video/youtube" --no-dry-run --force
```

#### Individual Steps

You can also run each step independently:

**Step 1 & 2: Map Message-IDs to Gmail IDs**

Takeout emails use RFC822 `Message-ID` headers (`:email/id` in the DB). Gmail
API uses its own internal IDs. `scripts/map_gmail_ids.clj` bridges this gap:

```bash
# Query emails and map their IDs
./takeout -d emails.db query -l "trading" -n 100 --format edn \
  | bb scripts/map_gmail_ids.clj -o /tmp/gmail-ids.edn --no-dry-run
```

This calls `gws gmail users messages list` with a `rfc822msgid:` query for each
email, and writes a mapping file:

```clojure
{"<Message-ID-1@mail.example.com>" {:gmail-id "18a3f4c5d6e7f8g9"
                                     :thread-id "18a3f4c5d6e7f8g8"},
 "<Message-ID-2@mail.example.com>" {:gmail-id "28b4g5d6e7f8h9i0"
                                     :thread-id "28b4g5d6e7f8h9i9"},
 ...}
```

Then update the database:

```bash
./takeout -d emails.db update-message-ids --from /tmp/gmail-ids.edn
```

**Options for `map_gmail_ids.clj`:**

| Option | Description | Default |
|--------|-------------|---------|
| `-o, --out-file PATH` | Output EDN file | `gmail-ids.edn` |
| `-d, --delay MS` | Delay between API calls | `1000` |
| `-n, --limit N` | Max IDs to process | `0` (unlimited) |
| `-s, --skip N` | Skip first N (for resume) | `0` |
| `--user-id ID` | Gmail user ID | `me` |
| `--no-dry-run` | Actually call API | |
| `--force` | Re-fetch even if already mapped | |

**Step 3 & 4: Fetch and Store Email Bodies**

Once emails have Gmail IDs, `scripts/fetch-bodies.clj` fetches full content:

```bash
# Fetch full bodies for emails with empty body
./takeout -d emails.db query -l "trading" --format edn \
  | bb scripts/fetch-bodies.clj -o /tmp/bodies.edn --no-dry-run

# With rate limiting (500ms between calls)
./takeout -d emails.db query -l "trading" --format edn \
  | bb scripts/fetch-bodies.clj -o /tmp/bodies.edn -d 500 --no-dry-run
```

This calls `gws gmail +read --id <gmail-id>` for each email and writes the
full body data. Then update the database:

```bash
./takeout -d emails.db update-bodies --from /tmp/bodies.edn
```

**Options for `fetch-bodies.clj`:**

| Option | Description | Default |
|--------|-------------|---------|
| `-o, --out-file PATH` | Output EDN file | `bodies.edn` |
| `-d, --delay MS` | Delay between API calls | `500` |
| `-n, --limit N` | Max emails to fetch | `0` (unlimited) |
| `--no-dry-run` | Actually call API | |

### Saving Attachments to Google Drive

The `gws` skill can also save email attachments directly to Google Drive:

```bash
# Download a specific attachment
gws gmail users messages attachments get \
  --userId me \
  --messageId <gmail-id> \
  --id <attachment-id> \
  > report.pdf

# gws can be scripted to automate:
# fetch attachment → save to Drive → share link
```

Since Takeout ingestion captures attachment metadata (`:email/attachments`),
you can query for emails with attachments, map their Gmail IDs, and then use
`gws` to fetch and save specific attachments to Drive:

```clojure
;; Find emails with attachments
(require '[astanova.db :as db])
(require '[datalevin.core :as d])

(let [conn (db/create-conn "emails.db")
      db   (d/db conn)]
  (d/q '[:find [(pull ?e [:email/id :email/subject :email/attachments]) ...]
         :where [?e :email/attachments ?att]]
       db))
```

Then, for each email, use `map_gmail_ids.clj` to get the Gmail ID, and `gws` to
download the attachment.

### Ingesting Emails via gws (Without Takeout MBOX)

For emails that aren't part of a Takeout export (e.g., recent emails, or
specific labels you want to pull live), you can use `gws` to fetch emails
directly and import them into the database:

```bash
# List recent message IDs for a Gmail query
gws gmail users messages list --params '{"userId":"me","q":"label:trading","maxResults":50}'

# Fetch each message and convert to DB format
gws gmail +read --id <gmail-id> --headers --format json
```

This approach complements the MBOX-based bulk ingestion by providing a live
sync path.

### Rate Limiting and Quotas

Gmail API has usage limits. The scripts include configurable delays:

- `map_gmail_ids.clj`: default 1000ms between calls
- `fetch-bodies.clj`: default 500ms between calls
- Adjust with `-d`/`--delay` if you hit rate limits (e.g., `-d 2000`)

### Complete Example: Enrich a Label from Scratch

```bash
# 1. Ingest from Takeout MBOX (if not already done)
./takeout -d emails.db ingest ~/Takeout/Mail/

# 2. Run the full pipeline for a specific label
bb scripts/update-label-pipeline.clj "trading" --no-dry-run -n 200

# 3. Verify
./takeout -d emails.db query -l "trading" -n 5 --format table

# 4. Generate a report for NotebookLM
./takeout -d emails.db query -l "trading" -n 50 --format edn \
  | bb scripts/report.clj -o trading-report.md
```

This gives you a complete, enriched email dataset with full bodies, ready for
both NotebookLM analysis and programmatic querying.
