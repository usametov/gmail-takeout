# Implementation Plan

Status: **active**. The first low-hanging slice is implemented; next slices are
listed in dependency order.

Companions:

- [Task Extraction Design](gws-task-extraction-design.md)
- [Assertion Workflow](assertion-workflow.md)
- [Entity Generation](entity-generation.md)
- [Backlog Triage](backlog-triage.md)

## Guiding rules

- Keep the production database out of experimental code paths.
- Prefer pure Clojure namespaces with JVM tests; Babashka scripts stay thin.
- Every slice must leave `clojure -M:test` and `clojure -T:build ci` green.
- Do not auto-approve assertions, promote entities, or write ontology cache
  entries until the explicit review/automation gates exist.
- Small, reversible changes; no destructive DB operations without a backup.
- Gmail/gws work uses fixtures; live calls are opt-in.

## L0 — completed foundation

| Item | Status | What exists |
|---|---|---|
| Task manifest loader/validator | done | [src/astanova/tasks/manifest.clj](../src/astanova/tasks/manifest.clj) |
| Assertion identity/model | done | [src/astanova/tasks/assertions.clj](../src/astanova/tasks/assertions.clj) |
| Atomic task output writer | done | [src/astanova/tasks/output.clj](../src/astanova/tasks/output.clj) |
| arXiv task manifest + scaffold | done | [scripts/tasks/arxiv-agentic-kg](../scripts/tasks/arxiv-agentic-kg) |
| Tests for L0 | done | `tasks_manifest_test`, `tasks_assertions_test`, `tasks_output_test` |

The scaffold validates `task.edn` and prints the task shape. It does not fetch,
extract, filter, or write task outputs yet.

## L1 — first end-to-end task slice

Order matters because each step unlocks the next.

| Step | Scope | Why now | Acceptance |
|---|---|---|---|
| L1a | Assertion persistence: schema for `:assertion/*`, `:assertion-revision/*`; `takeout assertions import` | Manifest/assertion model exists; persistence unblocks review | Import candidate assertions idempotently; subject must exist; tests |
| L1b | Correction/revision persistence: `:correction/*`, `:correction-proposal/*`; `takeout corrections import/list/add`; review file | Human quality control is the core requirement | Approve/reject/correct; retire correction; revisions append; no overwrite |
| L1c | Fake/raw fetch adapter: load a recorded gws/email fixture into the task raw format; validate canonical IDs and run metadata | Lets extraction/filter be built without Gmail | Raw fixture → raw export + run record; tests |
| L1d | arXiv extractor + EDN filter: title/abstract/URL/authors/categories; include agentic KG/ontology; Cypher/Datalog ranking | Proves the task shape on the real target | Golden fixture → expected include/exclude decisions; assertions; no network |
| L1e | `run.clj` extracts/filters and writes emails/assertions/entities/run atomically | First useful task output | Email array importable; assertions sidecar; run coverage; `--dry-run` |
| L1f | End-to-end arXiv task from raw fixture + review file | First complete loop | Import emails/assertions, review, export assertions, rerun diff |
| L1g | Live gws fetch behind `--fetch-live` | Only after offline path is solid | Auth/error/rate-limit handling; recorded fixture tests; live smoke |

### Non-negotiable behavior in L1

- No auto-approval.
- Assertion import requires the subject email/entity to exist.
- Corrections are never overwritten by re-extraction.
- Every correction/approval has a revision and audit entry.
- Output files are written atomically.
- `--dry-run` writes nothing.
- Task fetch is read-only.

## L2 — quality and automation

| Item | Depends on | Notes |
|---|---|---|
| Assertion review metrics | L1a/L1b | candidates/approvals/rejections/corrections by extractor/version/field |
| LLM correction proposals | L1b | evidence bundle, prompt/model log, human acceptance |
| Auto-approval policy (L1/L2) | L2 metrics + gold set | calibrated thresholds, sampled review, revocation |
| Embedding schema + re-embedding | Datalevin 1.2.11 | `:db.type/vec`; stale/source-hash lifecycle |
| Safe query DSL + observation envelope | assertions/entities | named ops only, coverage/as-of/sensitivity |
| Agent run/observation audit namespace | query DSL | separate from ontology facts |
| Semantic search | embeddings | hybrid lexical+vector, approximate labeling |

## Other tracks (parallelizable)

These do not block the task-extraction slice:

| Track | Low-hanging next step | Notes |
|---|---|---|
| E2c entity terms | Resolve job company/role/location to cache terms; provisional terms for misses | Existing [entity store](../src/astanova/entity/store.clj) |
| E4 TV entities | Channel/EDN + program/episode generator from fixtures | [Entity Generation](entity-generation.md) |
| O4 candidate compiler | DB-first candidate extraction using shared rules | Ontology track |
| O5 local matcher | Schema-valid body_extract | Ontology track |
| O6 classifier relocation | `src/` classifier + `takeout ontology` | Do after task foundation if priorities collide |
| G3/G4 gws provenance | body/label source metadata | Optional until live enrichment |
| T15/T24 docs | MboxIterator limits / 10K body | Quick docs wins |

## Recommended sequence

1. **L1a** assertion persistence/import (schema + CLI + tests).
2. **L1b** corrections/review/revision history.
3. **L1c + L1d** offline arXiv extraction/filter with fixtures.
4. **L1e + L1f** atomic task output + end-to-end offline task.
5. **L1g** live gws fetch.
6. In parallel: **E2c** term resolution and **T15/T24** docs.

## Acceptance for the first milestone

- `bb scripts/tasks/arxiv-agentic-kg/run.clj --validate` works.
- `run.clj` extracts/filters a recorded arXiv fixture and writes:
  - importable `emails.json`
  - `assertions.edn`
  - optional `entities.edn`
  - `run.edn` with query/window/coverage
- `assertions import` stores candidates only when subjects exist.
- `assertions review` can approve/reject/correct; corrections are separate and
  never overwritten.
- Re-running the task produces a diff, not duplicate/overwritten records.
- Tests cover manifest, extraction, filter, assertion IDs, import, review,
  correction precedence, and atomic output.
- Full suite/build green.

## Risks

- arXiv HTML changes; use fixtures and tolerant extraction.
- Gmail query/window semantics; conservative stale handling.
- LLM/local-model nondeterminism; cache by source/model/prompt/params.
- Datalevin vector index dimensions/version coupling; pin embedding model
  versions.
- Scope creep into the whole ontology pipeline; keep task extraction vertical
  and testable.
