# gmail-takeout

Ingest, search, and export Google Takeout MBOX files using Clojure + Datalevin.

## Installation

Requires Clojure CLI and Datalevin's native library (see [Design Notes](doc/design-notes.txt)).

```bash
git clone git@github.com:usametov/gmail-takeout.git
cd gmail-takeout
```

## Usage

```bash
# Ingest MBOX files
clojure -M:run-m ingest ./Mail/*.mbox

# Search emails
clojure -M:run-m query --label Work -n 5

# Show statistics
clojure -M:run-m stats

# Export as JSON
clojure -M:run-m export --format json out.json

# Help
clojure -M:run-m --help
```

## Architecture

```
src/astanova/
  takeout.clj   - Main entry point
  cli.clj       - CLI: argument parsing, dispatch, formatting
  db.clj        - Datalevin schema + connection
  parse.clj     - MBOX parsing (Mime4j + Jakarta Mail)
  ingest.clj    - Ingestion pipeline
```

## License

Copyright © 2026 Asel

Distributed under the Eclipse Public License 2.0.
