# astanova/takeout

Import and query your Google Takeout email archive using Datalevin.

## Overview

`astanova/takeout` ingests MBOX files exported from Google Takeout into a local [Datalevin](https://github.com/juji-io/datalevin) database, then provides a rich query interface for searching, filtering, and analyzing your email archive.

## Table of Contents

- [Setup](setup.md) — Prerequisites, `~/.secrets.edn`, and first-run checks
- [Ingest Guide](ingest-guide.md) — How to import MBOX files into the database
- [Query Guide](query-guide.md) — Complete reference for querying emails, threads, and analytics
- [CLI Query Guide](cli-query-guide.md) — All CLI commands and options
- [Label Management](label-management.md) — Label propagation and diagnostics
- [Ontology Classifier](ontology-classifier.md) — DeepSeek ontology classification, cache enforcement, and audit
- [Legacy Gmail Pipeline Port Intent](legacy-pipeline-port-intent.md) — DB-first port scope, ontology invariants, phases
- [Ontology Reasoning](ontology-reasoning.md) — RDFS + OWL 2 RL subset, Datalevin rules, closed-world validation
- [GWS Enrichment](gws-enrichment.md) — Canonical Gmail IDs, body/label enrichment, live sources
- [Entity Generation](entity-generation.md) — Jobs, TV channels/programs/episodes, evidence, upsert
- [gws Task Extraction Intent](gws-task-extraction-intent.md) — Replayable task-specific gws fetch/extract/filter/output
- [gws Task Extraction Design](gws-task-extraction-design.md) — Manifest, assertions, corrections, safe query DSL, observation envelope
- [Assertion Workflow](assertion-workflow.md) — Candidate/approval/correction lifecycle, effective values, re-extraction diffs
- [Implementation Plan](implementation-plan.md) — Low-hanging slices and dependency-ordered task rollout
- [Backlog Triage](backlog-triage.md) — Remaining bugs and the port roadmap
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
- **Thread tracking** — Thread grouping via X-GM-THRID, References, or Thread-Topic
- **Gmail labels** — Preserves X-Gmail-Labels from Takeout exports
- **Analytics** — Top senders, label distribution, date-range filtering
- **CLI & library** — Use as a command-line tool or from Clojure code