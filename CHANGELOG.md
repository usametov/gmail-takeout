# Changelog

All notable changes to `astanova/takeout` are recorded here.

## Unreleased

### Added

- Entity/evidence graph schema and entity classes for job postings, TV channels,
  programs, and episodes.
- `astanova.entity` deterministic IDs, job dedup keys, canonical LinkedIn URLs,
  category-aware validation, and evidence IDs.
- LinkedIn job-card parser and `scripts/generate-jobs.clj` generator.
- Antigravity JSON/EDN email import (`takeout import-emails`) with canonical
  Gmail IDs, HTML/text separation, label/link unions, and body preservation.
- Entity/evidence upsert (`takeout upsert-entities`) with class seeding,
  idempotent entity/evidence storage, and dry-run.
- Entity/LinkedIn fixtures and tests.
- Canonical Gmail ID helpers (`astanova.gmail-id`) and RDF-style message/thread
  IRIs; API hex IDs convert to the decimal Takeout form at the boundary.
- Ontology graph schema (`ontology/schema.edn`) and Datalog rules
  (`ontology/rules.edn`) for RDFS-style subclass/type inference, sameAs,
  broader/narrower/related, and label ancestry.
- Shared ontology rule namespace with golden tests for normalization, noise
  precedence, canonical aliases, cache consistency, and graph validation.
- `./takeout` source-checkout wrapper.
- Checked-in parser/integration fixtures under `test/fixtures/`.
- Regression coverage for Gmail IDs, thread IDs, UTF-8 MBOX splitting,
  subject-less emails, nil transaction values, and CLI output shapes.
- `query --no-body` for EDN/JSON pipelines that do not need body text.
- Deterministic newest-first query ordering for stable pagination.
- `gws` installation/authentication and Google Takeout ZIP sections in the
  setup/CLI guides.

### Fixed

- `update-message-ids` normalizes Gmail API hex IDs to canonical decimal,
  rejects synthetic/dry-run IDs, migrates old hex IDs, and refuses identity
  conflicts instead of splitting threads.
- `map_gmail_ids` writes canonical decimal IDs (with API hex retained for
  callers); `fetch-bodies` converts back to API hex for gws.
- The ontology classifier now uses the shared rule namespace instead of owning
  a second copy of category/noise/cache logic.
- Thread IDs now prefer `X-GM-THRID`, then the first `References` Message-ID,
  then `Thread-Topic`.
- Gmail IDs are only read from numeric `From_` envelopes; standard mbox
  envelopes no longer produce bogus IDs.
- Prose lines beginning with `From ` no longer shift Gmail-ID/message pairing.
- Empty `text/plain` MIME alternatives no longer hide the HTML body.
- MBOX splitting is byte-correct for UTF-8 and copies complete chunks
  (`transferFrom` destination position).
- Ingestion streams parsed messages in batches instead of realizing an entire
  file in memory.
- Nil `:email/gmail-id`, `:email/thread-id`, and `:content/labels` values no
  longer abort transactions.
- `stats` handles empty databases and threads without subjects.
- `query --subject` is case-insensitive; subject-less emails appear in default
  queries and counts.
- `inspect-thread` handles unknown/empty threads.
- `export -l` no longer swallows the output filename; single and multiple label
  filters build valid queries.
- `update-bodies` records the full body length before truncation.
- `--debug` prints the complete MIME tree.
- Thread summaries/listing use metadata-only projections; `query --format table`
  avoids pulling bodies and links.
- JSON output for `labels`, `addresses`, and `linked-labels` is valid and
  consistent with EDN; machine-readable summaries go to stderr.
- `threads` and `propagate` honor `--format`; `threads` honors `--limit`.
- `split` accepts directories.
- `parse-date` throws instead of terminating the JVM; empty table results no
  longer call `System.exit`.
- `update-*` commands close the database connection on failure.
- `generate-fallback-id` is locale/timezone independent.
- Removed the `./takeout.log` side effect at namespace load.

### Changed

- Removed unused Tika and Portal dependencies.
- Pipeline scripts accept `TAKEOUT_DIR`, `TAKEOUT_DB`, `TAKEOUT_MAIL_DIR`,
  `TAKEOUT_BIN`, `TAKEOUT_ROOT`, and `TAKEOUT_SCRIPTS` instead of relying only
  on hard-coded home paths.
