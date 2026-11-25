# Ontology data

This directory is the default data root for `scripts/ontology_classifier.clj`.
It was copied from the legacy Gmail ontology project so the classifier,
config, cache and candidates live with `gmail-takeout`.

| File | Purpose | Tracked? |
|------|---------|----------|
| `config.json` | Categories, DF threshold, allow/stop lists, canonical aliases | yes |
| `raw_candidates.json` | Proper-noun candidates from the upstream compiler | yes |
| `ontology_cache.json` | Classified/normalized cache; the source of truth for local matching | yes |
| `ontology_audit.jsonl` | Append-only decision log written by the classifier | ignored |
| `*.bak`, `*.report.json`, `*.tmp` | Backups/reports/temporary files | ignored |

## Use

```bash
# From the repo root; `ontology/` is auto-detected.
bb scripts/ontology_classifier.clj --check
bb scripts/ontology_classifier.clj --dry-run
bb scripts/ontology_classifier.clj --limit 100
```

Override the data root with `--base-dir` or `GMAILS_BASE_DIR`; individual paths
can be overridden with `--config`, `--candidates`, `--cache` and `--audit-log`.
See [doc/setup.md](../doc/setup.md) and
[doc/ontology-classifier.md](../doc/ontology-classifier.md).

## Curation

- Keep `ontology_cache.json` under version control; review changes with
  `git diff ontology/ontology_cache.json`.
- Use `--check` before and after a classification run.
- Use `--normalize-cache` deliberately when migrating spelling variants.
- The audit log is append-only and intentionally not tracked.
