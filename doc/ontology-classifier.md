# Ontology Classifier & Duplicate Collapser

Status: **new in `gmail-takeout`**, ported from the Python
`antigravity-gmail-project` pipeline.

This document is the contract for `scripts/ontology_classifier.clj`. It
replaces the earlier Gemini-oriented draft. `gmail-takeout` uses **DeepSeek**
for ontology classification; it does not call Gemini.

## Purpose

Enforce a stable, deterministic ontology over proper-noun candidates extracted
from Gmail (and LinkedIn) threads. Classification and normalization happen once
(or incrementally) and are cached. After the cache is warm, all email
processing is pure local lookup with zero LLM cost.

The classifier is the middle stage of the pipeline (upstream step 3):

| Stage | Tool | Output |
|-------|------|--------|
| 1. Compile raw candidates | upstream candidate compiler | `raw_candidates.json` |
| 2. Classify + collapse | **this script** | `ontology_cache.json` (+ audit/report) |
| 3. Local matching | `fetch_emails.py` / `fetch_linkedin_emails.py` | `emails.json`, `linkedin_emails.json`, … |

The cache is the single source of truth after classification. No per-email LLM
calls are allowed in local matching.

## Ontology Rules (must hold)

### Categories

Exactly one category per term, from `config.json :categories` (default five):

- `companies` — brands, organizations, platforms, employers
- `roles` — job titles / positions
- `fields` — technologies, frameworks, libraries, skills, concepts
- `job_types` — work classifications (`Full-time`, `Contract`, `Part-time`)
- `locations` — geographies, cities, provinces, regions, or `Remote`

The category enum is configurable so a sixth category (for example `people` or
`products`) can be added without invalidating existing entries. Add it to
`categories` and set/migrate cache entries deliberately; the script rejects
categories outside the configured enum.

### Normalization / collapse rules

- Abbreviations become their canonical form (`AAPL` → `Apple`, `ML` →
  `Machine Learning`).
- Spacing, hyphen, underscore, dot, apostrophe and slash variants collapse to
  one form (`LiquidAI`, `Liquid-AI` → `Liquid AI`; `HttpKit` → `HTTP-Kit`).
- Capitalization is standardized (`google` → `Google`, `dotnet` → `Dotnet`).
- The same normalized term always receives the same category.
- Canonical mappings in `config.json` are authoritative.

### Noise and allow-list rules

Applied locally before any API call; the allow-list always wins:

- `user_allow_list`: force-keep specific terms regardless of frequency or
  other noise rules (`Toronto`, `Contract`, …).
- `user_stop_list`: drop specific boilerplate terms.
- `standalone_generic`: drop isolated generic words (`Senior`, `Developer`) but
  keep them inside multi-word roles (`Senior Full-Stack Developer`).
- `auto_detected_df_stops` (written by the candidate compiler) and candidate
  `df` metadata above `df_threshold`: treat high-frequency terms as boilerplate
  unless allow-listed.
- Safety filters: numeric/hash-only strings, over-long terms, URL fragments,
  sentence fragments (`AI. But`), and too-short terms (except `AI`, `ML`, `UI`,
  `UX`, `IP`). These mirror the upstream compiler and prevent wasted tokens.

### Cache contract

- Keys: exact original string **and** lowercase version.
- Value: `{"category": "…", "normalized": "…"}` only. Metadata such as source
  and confidence lives in the audit log, not in the cache, so the contract
  stays compatible with `fetch_emails.py`.
- The cache is updated atomically (temporary file + move).
- The cache is updated only with validated classifications.

## DeepSeek Provider

The script speaks the OpenAI-compatible chat-completions API. It is not tied to
the Gemini SDK and has no Gemini code path.

### Credentials

Resolution order:

| Setting | Sources (highest first) |
|---------|--------------------------|
| API key | `DEEPSEEK_API_KEY`, `~/.secrets.edn` `:deepseek-api-key` |
| Base URL | `--base-url`, `DEEPSEEK_BASE`, `DEEPSEEK_BASE_URL`, `~/.secrets.edn` `:deepseek-base`, `config.json` `base_url`, `https://api.deepseek.com` |
| Model | `--model`, `DEEPSEEK_MODEL`, `config.json` `model`, `deepseek-v4-flash` |

`--secrets-file` selects a different EDN secrets file. The API key is never
printed, written to the audit log, or included in reports. A key is required
only when there are new terms to classify; `--check` and `--dry-run` work
without one.

### Request

- `POST <base>/chat/completions` (`/v1` is handled; a full
  `/chat/completions` base is accepted)
- `response_format: {"type": "json_object"}` (disable with `--no-json-mode` for
  local models that reject it; the script also falls back automatically on a
  relevant HTTP 400)
- Retries with backoff on HTTP 408/409/425/429/5xx and network errors
- A truncated response (`finish_reason == "length"`) is split and retried
- HTTP/auth failures abort the run before the cache is written

## Enforcement Pipeline

Each run performs these steps in order:

1. **Load and validate** `config.json`, `raw_candidates.json` and the cache.
   A malformed cache is an error, never silently replaced.
2. **Deduplicate candidates** case-insensitively while keeping the first
   spelling.
3. **Diff against the cache** using both exact and lowercase keys. `--force`
   reclassifies cached terms.
4. **Apply local noise rules** and the allow-list. Canonical aliases that fully
   specify a normalized form and category are resolved locally; no API call.
5. **Batch** the remaining terms (default 100) and send only those to DeepSeek.
6. **Validate the response**:
   - top-level `classifications` array
   - one entry per requested term; unknown extra terms are rejected
   - `raw_term` must match a requested term (case-insensitive match accepted)
   - category must be in the configured enum (singular synonyms such as
     `company` are normalized defensively)
   - normalized form must be non-blank, ≤ 120 characters, free of control
     characters
   - missing or schema-invalid terms are retried up to `--max-retries`
7. **Enforce consistency**:
   - group accepted terms by a normalization key that ignores case, whitespace,
     hyphen, underscore, dot, apostrophe and slash
   - choose one canonical spelling and one category per group
   - precedence: explicit canonical alias → existing cache → model majority
   - record overrides/conflicts; never create a second category for an existing
     normalized form
8. **Update the cache atomically** with both exact and lowercase keys.
9. **Append the audit log** (JSONL) and optionally write a JSON/EDN report.

## Configuration

`config.json` remains compatible with the Python project. New keys are
optional.

| Key | Default | Meaning |
|-----|---------|---------|
| `categories` | five built-ins | Allowed category enum |
| `df_threshold` | `0.25` | DF threshold, 0.0–1.0 |
| `user_allow_list` | `[]` | Terms always kept |
| `user_stop_list` | `[]` | Terms always dropped |
| `standalone_generic` | `[]` | Generic words dropped only when standalone |
| `auto_detected_df_stops` | `[]` | High-DF terms from the compiler |
| `canonical_aliases` | `{}` | Authoritative normalization table |
| `schema_version` | `1` | Recorded in reports/audit |
| `model` | — | Optional DeepSeek model override |
| `base_url` | — | Optional API base override |
| `batch_size` | `100` | Terms per request |
| `max_retries` | `2` | Retries for transport and missing-term repair |
| `delay_ms` | `1000` | Delay between batches |
| `retry_backoff_ms` | `1500` | Base backoff between retries |
| `temperature` | `0.1` | Sampling temperature |
| `max_tokens` | `8000` | Completion budget |
| `timeout_ms` | `120000` | HTTP timeout |
| `json_mode` | `true` | Send `response_format=json_object` |

### Canonical aliases

Three equivalent forms are accepted:

```json
{
  "canonical_aliases": {
    "AAPL": {"normalized": "Apple", "category": "companies"},
    "HttpKit": {"normalized": "HTTP-Kit"},
    "liquidai": "Liquid AI"
  }
}
```

- `{"normalized", "category"}` — fully resolved locally, never sent to the model
- `{"normalized"}` — sent for a category, but the spelling is forced
- `"Liquid AI"` — same as above, shorthand

Keys are matched case-insensitively. An alias category outside the configured
enum is a configuration error.

### Candidate input

`raw_candidates.json` may be a JSON array of strings (current compiler output)
or an array of objects:

```json
[{"term": "Toronto", "df": 0.12}, "Datalog"]
```

Map entries may use `term`, `raw_term`, `value`, `text` or `candidate`, and
`df` / `document_frequency`. The classifier does not rescan email bodies; DF is
supplied by the compiler or reflected in `auto_detected_df_stops`.

## CLI

```bash
# Validate config/cache/candidates (no API key needed)
bb scripts/ontology_classifier.clj --check

# Inspect the diff and batch plan (no API calls, no writes)
bb scripts/ontology_classifier.clj --dry-run

# Classify new terms and update the cache
export DEEPSEEK_API_KEY='...'
bb scripts/ontology_classifier.clj --base-dir ~/code/antigravity-gmail-project

# Cost control: send at most 200 new terms this run
bb scripts/ontology_classifier.clj --limit 200

# One-time migration: canonicalize existing cache values too
bb scripts/ontology_classifier.clj --normalize-cache

# Write a machine-readable report
bb scripts/ontology_classifier.clj --report /tmp/ontology-report.json
```

| Option | Description |
|--------|-------------|
| `-b, --base-dir DIR` | Data root; default `$GMAILS_BASE_DIR`, `~/code/antigravity-gmail-project`, `~/code/gmails`, then cwd |
| `--config PATH` | `config.json` (or `.edn`) |
| `--candidates PATH` | `raw_candidates.json` (or `.edn`) |
| `--cache PATH` | `ontology_cache.json` (or `.edn`) |
| `--audit-log PATH` | Append-only JSONL audit log (default `<base>/ontology_audit.jsonl`) |
| `--report PATH` | Write JSON/EDN report; also works with `--check` |
| `-m, --model MODEL` | DeepSeek model id |
| `--base-url URL` | OpenAI-compatible endpoint |
| `--secrets-file PATH` | EDN secrets file (default `~/.secrets.edn`) |
| `-n, --batch-size N` | Terms per request (default 100) |
| `-l, --limit N` | Max new terms this run (default all) |
| `--max-retries N` | Retry count for transport and missing-term repair |
| `--delay MS` | Delay between batches |
| `--timeout MS` | HTTP timeout |
| `--temperature F` | Sampling temperature |
| `--max-tokens N` | Completion budget |
| `--retry-backoff MS` | Base retry backoff |
| `--force` | Reclassify cached terms |
| `--normalize-cache` | Rewrite existing cache values to canonical spellings |
| `--dry-run` | Plan only; no API and no writes |
| `--check` | Validate only; exit non-zero on invalid cache/conflicts |
| `--no-json-mode` | Omit `response_format=json_object` |
| `--no-audit` | Do not write the audit log |
| `--verbose` | Stack traces and extra detail |
| `-h, --help` | Usage |

`--normalize-cache` can rewrite many values at once. Run `--check` first and
keep a backup of the cache; for an existing category conflict it uses the
majority category and records the conflict in the report.

Exit codes: `0` success; `1` validation/API/classification failure (for
example, terms still missing after retries); `2` usage error.

## Audit and Report

The audit log is append-only JSONL. One line per decision, for example:

```json
{"ts":"2026-11-24T04:44:11.894Z","event":"classified","provider":"deepseek",
 "model":"deepseek-v4-flash","raw_term":"MachineLearning",
 "normalized":"Machine Learning","category":"fields","source":"cache",
 "confidence":0.4,"category_overridden":true,"normalized_overridden":true}
```

Events: `classified`, `rejected`, `conflict`, `cache-normalized`,
`cache-write`. The log never contains the API key.

`--report PATH` writes a JSON (or `.edn`) object with counts, filtered terms
and reasons, rejected terms, conflicts, collapsed groups, enforcement
overrides, and the cache before/after diff.

## Local Model Evaluation Step

The production default is DeepSeek. Before switching production classification
to a local model, evaluate it against the same contract:

1. Build a stratified gold set of 200–500 terms from the existing cache, plus
   hard cases (known collapses, ambiguous categories).
2. Run candidate local models (Qwen, DeepSeek variants, Phi, etc.) through
   llama.cpp/Ollama with an OpenAI-compatible endpoint.
3. Measure JSON-schema compliance, category exact-match, normalization quality,
   and throughput/VRAM on the target workstation.
4. Use constrained decoding/GBNF where available; otherwise strong few-shot
   prompting plus `--max-retries`.
5. Adopt a local model only if it is within ~5–10 % of DeepSeek on category and
   normalization at acceptable latency; otherwise keep DeepSeek for new-term
   batches and use local models for offline/high-volume work.

Because the client is OpenAI-compatible, evaluation uses the same script:

```bash
DEEPSEEK_API_KEY=local DEEPSEEK_MODEL=qwen2.5:14b \
  bb scripts/ontology_classifier.clj \
  --base-url http://localhost:11434/v1 --no-json-mode --dry-run
```

Replace `--dry-run` for a live evaluation run against a **copy** of the cache.
Record the result (`--report`) before any production cut-over. No separate
evaluation harness is bundled yet; `--check` is the regression gate for cache
integrity, and a gold-set harness is the next step.

## Compatibility with the Antigravity Pipeline

- `fetch_emails.py` and `fetch_linkedin_emails.py` read
  `ontology_cache.json` and look up candidates with `cache.get(term)` or
  `cache.get(term.lower())`. The dual-key contract is preserved.
- Cache values remain exactly `{"category", "normalized"}`. Additional metadata
  is not injected into the cache.
- `raw_candidates.json` and `config.json` remain readable by the Python
  pipeline. New config keys are optional and ignored by older code.
- The script does not modify `config.json`; auto-detected DF stops are still
  written by the upstream candidate compiler.

## Enforcement Checklist

- [x] New terms only reach the model after the cache diff
- [x] Output validates against the configured category schema
- [x] Invalid/missing terms are retried; permanent failures are reported
- [x] Near-duplicates collapse to one normalized form
- [x] A normalized form never receives two categories
- [x] Canonical mappings are applied deterministically
- [x] High-DF boilerplate is dropped unless allow-listed
- [x] `user_allow_list`, `user_stop_list`, `standalone_generic` are enforced
- [x] Cache is updated atomically with both casings
- [x] Cache is only updated with validated classifications
- [x] Local matching never calls an LLM
- [x] Audit log records source/model/confidence/overrides
- [x] `--check` validates cache integrity and conflicts
- [ ] Local-model evaluation results recorded before production cut-over

## Known Limitations

- The classifier does not recompute document frequency from email bodies; it
  consumes compiler metadata (`df`) or `auto_detected_df_stops`.
- Canonical spellings are chosen from observed variants and aliases; the script
  cannot invent a better spelling that was never seen.
- Local evaluation/gold-set tooling is not bundled.
- The safety filters intentionally mirror the upstream compiler, including the
  symbol-heavy/URL-fragment rules; use `user_allow_list` for legitimate terms
  that those rules would otherwise drop.
