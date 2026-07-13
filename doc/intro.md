# astanova/takeout

Import and query your Google Takeout email archive using Datalevin.

## Overview

`astanova/takeout` ingests MBOX files exported from Google Takeout into a local [Datalevin](https://github.com/juji-io/datalevin) database, then provides a rich query interface for searching, filtering, and analyzing your email archive.

## Table of Contents

- [Ingest Guide](ingest-guide.md) — How to import MBOX files into the database
- [Query Guide](query-guide.md) — Complete reference for querying emails, threads, and analytics
- [CLI Query Guide](cli-query-guide.md) — All CLI commands and options
- [Label Management](label-management.md) — Label propagation and diagnostics
- [Design Notes](design-notes.md) — Architecture decisions and schema design

## Quick Start

```bash
# Ingest an MBOX file
./takeout -d emails.db ingest ~/Takeout/Mail/example.mbox

# Query by subject
./takeout -d emails.db query -s "meeting"

# Get statistics
./takeout -d emails.db stats

# Start a REPL for ad-hoc queries
clojure -M:repl/conjure
```

## Features

- **MBOX ingestion** — Parse Google Takeout MBOX files with full MIME support
- **Deduplication** — Built-in upsert via Message-ID
- **Full-text search** — Search across subject and body
- **Thread tracking** — Thread grouping via References, Thread-Topic, or X-GM-THRID
- **Gmail labels** — Preserves X-Gmail-Labels from Takeout exports
- **Analytics** — Top senders, label distribution, date-range filtering
- **CLI & library** — Use as a command-line tool or from Clojure code