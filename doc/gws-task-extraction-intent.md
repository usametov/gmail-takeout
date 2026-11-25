# Replayable gws Extraction Tasks — Intent

Status: **intent approved**. Design in progress:
[Task Extraction Design](gws-task-extraction-design.md).

## Locked decisions (agentic querying)

- **Agent query surface:** constrained query templates / safe DSL. Agents do not
  generate arbitrary Datalog.
- **Observation trace:** agent runs and observations live in a separate audit
  namespace, not in ontology facts.
- **Coverage/as-of:** every query result reports source/class/date coverage and
  a snapshot/as-of time.
- **Sensitivity:** catalog-level sensitivity flags plus redaction defaults
  control agent-visible fields.
- **Latency:** measure first; materialize inference closure only for measured
  hot paths.

## Locked decisions (product shape)

- **Output:** a legacy-compatible email array with a `task_extract` extension,
  plus an optional entity sidecar file.
- **Packaging:** one self-contained script per task, with a manifest header,
  shared helpers, and fixtures under `scripts/tasks/<task>/`.
- **DB timing:** fetch → task JSON → import. Extraction/filtering also runs
  against the JSON without Gmail.
- **Derived entities:** task-scoped and staged until reviewed, then promoted.
- **arXiv filter direction:** **include** papers about agentic
  knowledge-graph/ontology patterns, with Cypher/Datalog as the
  reimplementation lens.

## Locked decisions (quality and provenance)

- **LLM use:** local deterministic extraction/filtering by default; an LLM step
  requires an explicit task flag and recorded model/prompt. Field-level
  provenance must leave room for LLM-produced values.
- **Provenance model:** reified field-value assertions, so multi-valued fields
  and corrections have stable identities.
- **Correction storage:** Datalevin correction entities plus an exportable
  EDN/JSON form; re-extraction never overwrites a correction.
- **Correction scope:** start with task/entity fields; extend to imported email
  fields only if needed.
- **Correction UX:** a `takeout corrections` command plus an EDN review file,
  never raw transactions.
- **Assertion import:** the subject email/entity must exist before import.
- **Correction retirement:** approving a new value can retire the old
  correction atomically.
- **Revision history:** assertions keep a separate append-only revision history
  for automation.
- **Bulk corrections:** per-record conflicts may use an explicit LLM proposal
  step with logged evidence/prompt/model; proposals require human acceptance.

## Forward constraints: local models and embeddings

These are not locked implementation choices yet. They constrain the design so we
do not have to re-architect later.

### Local model extraction

- A local model (LFM or similar) is an **extractor implementation**, not a new
  truth layer. It produces candidate assertions like any heuristic extractor.
- Heuristics remain the deterministic baseline and fallback. A task can choose
  heuristic, local-model, or hybrid extraction per field.
- Extractor model identity/version, prompt id, parameters, context window, and
  response hash are recorded in assertion provenance.
- Local-model outputs can be cached by `(source-hash, model, prompt, params)` for
  replay and cost control.
- A model upgrade is an extractor version change; it produces new assertions,
  not silent overrides.

### Partial automation

The assertion revision history is designed to support increasing automation:

| Level | Behavior |
|---|---|
| L0 | model/heuristic proposes; human reviews every assertion |
| L1 | model proposes; human reviews only low-confidence/conflicted subsets |
| L2 | auto-approve high-confidence values with sampled human review |
| L3 | fully autonomous approval (not planned) |

Automation policy must be explicit per task/extractor/field, backed by a gold
set, calibrated confidence, and rollback/retirement. Human corrections and
retirements remain auditable.

### Embeddings in Datalevin

- Embeddings are **derived indexes**, not ontology facts or assertions.
- Store model id/version, dimensions, normalization, source hash, and
  created-at with each vector.
- Changing the source or model makes an embedding stale; re-embedding creates a
  new embedding version.
- Sensitivity applies to embeddings too: an embedding of sensitive text may
  leak information even though it is not the text itself.
- The safe query DSL gains a semantic-search operation; results remain inside
  the standard observation envelope with coverage/as-of and model provenance.
- Hybrid lexical + vector search is preferred over vector-only for auditability.
- Agent loops may use embeddings for recall and candidate generation, not as
  proof of truth.

## Why

We can now import email exports produced elsewhere and upsert generated
entities. The next step is to use `gws` directly against the Gmail API for
**task-specific enrichment**:

- fetch a human-chosen slice of Gmail
- extract the properties that matter for that task
- filter the result with a human-chosen predicate
- write a JSON export that can be re-imported into our database
- keep the query/extraction/filter as a replayable Babashka script

This is intentionally not one giant Gmail pipeline. Each task has its own
query, extraction rules, and filter, but all tasks share the same shape and
storage path.

Example task:

> Fetch messages from `no-reply@arxiv.org`, parse paper titles, abstracts and
> URLs, then include papers about agentic knowledge-graph/ontology patterns
> that are candidates for reimplementation with Cypher/Datalog.

## Goal

Create a repeatable pattern for **gws extraction tasks**:

```text
Gmail query
    ↓
gws fetch (live)
    ↓
raw email export
    ↓
task extraction + filter (local, deterministic)
    ↓
task JSON export
    ↓
existing import-emails / entity upsert path
    ↓
Datalevin
```

Every task is replayable from its Babashka script, and every task's output is
compatible with the import path we already use.

## What is a “task”?

A task is a human-authored, versioned unit with four explicit parts:

1. **Fetch** — Gmail query, account, page/limit bounds, time window.
2. **Extract** — which properties to pull from each message
   (e.g. paper title, abstract, canonical URL, authors, categories), the
   extractor id/version, and the field-level provenance of each value.
3. **Filter** — the human-defined predicate that decides which messages or
   extracted records survive (include or exclude).
4. **Output** — the JSON file to write and the import/upsert command to run.

The Babashka script is the replay artifact. Running the script again with the
same query/window should reproduce the same task result, subject to Gmail
availability and message changes.

## Principles

### Human chooses the properties

No generic “extract everything” behavior. Each task declares:

- the Gmail query
- the fields it wants
- the extraction method (regex, MIME part, HTML/JSON-LD, link parsing, etc.)
- the filter predicate
- whether the output is an email export, derived entities, or both

### Replayability over one-off commands

- Each task lives in its own script under a predictable task directory.
- The script contains or references its query and extraction manifest.
- Defaults are explicit: time window, limit, output path.
- `--dry-run` shows what would be fetched and extracted without writing.
- A task can re-run extraction/filter on an already-fetched export when Gmail
  access is unavailable.

### Keep fetch separate from extraction/filter

The fetch step should produce a raw email export. Extraction and filtering
should be runnable against that export without calling Gmail again. This makes
the task testable, cheap to iterate on, and auditable.

### Output remains importable

The output should stay close to the JSON email exports we already import:

- same envelope/fields where possible (`messageId`, `gmailId`, `threadId`,
  `subject`, `from`, `to`, labels, dates, body)
- task-specific extracted properties in a clearly namespaced extension
- no silent schema drift in the core email fields

Exact output shape is a design decision. The intent is that
`takeout import-emails` can consume the result without a special import path.

### Derived entities are allowed, but staged

A task may produce derived entities (e.g. `:paper`) in addition to, or instead
of, enriched emails. Those properties should be task-scoped at first and only
promoted into the shared ontology after review.

Do not let task-specific vocabulary silently mutate the approved ontology cache.

### Deterministic local work by default

Extraction and filtering should be local and repeatable: regex, HTML parsing,
link extraction, ontology-cache lookup, explicit keyword/term predicates.
An LLM may be added only when a task explicitly asks for it, with the model and
prompt recorded. No hidden per-email LLM calls.

### Field-level provenance

A row-level `source` field is **not sufficient** for quality control. It tells
us which task produced an email record, but not which extractor produced a
specific wrong value.

Every extracted field/value should be attributable to:

- extractor id and version (e.g. `google-jobs-v1`)
- task/script and Gmail query
- source message and locator (MIME part, line, offset, regex/match)
- extraction method (`:regex`, `:html`, `:json-ld`, `:llm`, ...)
- raw value and normalized/effective value
- confidence if applicable
- observed-at timestamp

This makes questions like “show every record whose company came from
`google-jobs-v1`” or “which extractor produced this wrong company?” answerable.
A source field should therefore identify the **extractor/task**, and it should
be attached at the field-value level, not only to the row.

### Human correction is first-class

Extracted values are candidates; human-approved values are authoritative.

Corrections must be possible **after insert**, without editing the database by
hand and without losing the original extraction:

- keep the raw extraction, the effective value, and the correction record
  separate
- each correction records entity, field, old value, new value, reason,
  reviewer, timestamp, and the extractor/source scope
- re-running extraction must not silently overwrite a human correction; a new
  extraction becomes a candidate and produces a diff for review
- a correction can be bulk-applied to all records from a given extractor/version
- an extractor can be quarantined/disabled until its bug is fixed
- every correction and override is auditable

Field quality is therefore controlled by humans through review, correction,
and approval, not only by trusting the extraction routine.

### Provenance

Each output record should make it possible to answer:

- which Gmail query produced it
- which message it came from
- which properties were extracted vs. copied
- which filter accepted/excluded it
- when the task ran
- which extractor/version and field-level source produced each extracted value
- whether a value is raw, normalized, or human-corrected

### Secrets and safety

- `gws` credentials stay outside the repository.
- Scripts never print tokens or full auth config.
- Fetching is rate-limited/resumable, like the existing gws scripts.
- No destructive Gmail operations. Read-only scope.

## Scope

In scope:

- gws message search and fetch for task-specific queries
- full-message/raw MIME handling and label resolution
- per-task extraction of properties from headers, text, HTML, and links
- local filters with include/exclude predicates
- JSON output suitable for the existing import path
- replayable Babashka scripts and dry-run/replay support
- optional derived entities for task-specific records
- tests/fixtures for parser/filter behavior

Out of scope:

- general-purpose web crawling
- full Gmail mailbox synchronization
- automatic ontology classification of every task property
- per-email LLM enrichment by default
- Gmail write operations
- a UI for task authoring
- replacing the existing import/upsert commands

## Example task (illustrative, not the spec)

Task: **arXiv ontology papers**

- Fetch: `from:no-reply@arxiv.org` within a chosen time window.
- Extract:
  - paper title
  - abstract
  - arXiv URL / DOI
  - authors/categories if present
- Filter:
  - human-defined include/exclude predicate over the abstract and title
  - example dimension: knowledge-graph/ontology × Cypher/Datalog
- Output:
  - an import-compatible email export
  - task-specific fields for the paper extraction
  - optionally `:paper` entities for selected papers

The filter could be an include filter (“keep papers matching this topic”) or
an exclude filter (“drop papers matching this topic”). The task manifest must
state which one it is.

## Success criteria

- A new task can be added by writing one script and one manifest, without
  changing the core import path.
- Re-running a task with the same inputs produces the same selected set.
- Task output imports cleanly and does not duplicate emails.
- Every selected/excluded record has a visible reason and provenance.
- Every extracted field can be traced to an extractor/version and source
  locator.
- A wrong field can be corrected after insert without losing the raw value.
- Re-running the task produces a reviewable diff rather than overwriting human
  corrections.
- Quality metrics can report corrections/rejections per extractor, field, and
  version.
- Extraction/filter logic is testable without Gmail.
- Task-specific vocabulary does not pollute the approved ontology cache.
- A human can read the script/manifest and understand exactly what will be
  fetched, extracted, filtered, and written.

## Examples of the correction problem

Current row-level source fields already expose the issue:

- A Google Jobs notification may set company to `Google` because the sender is
  `notify-noreply@google.com`; the actual employer is elsewhere in the body.
- If the only provenance is “this email came from the Google Jobs task”, we
  cannot tell which records share the bad company extractor.

Desired outcome:

1. Mark the specific field/value wrong.
2. See the extractor id/version and locator that produced it.
3. Correct it for that record, or bulk-correct all records from that extractor.
4. Add a regression fixture.
5. Rerun extraction for the affected scope; review the diff instead of
   clobbering the correction.

## Agentic querying: think → query → observe

The system should be usable by an agent loop:

1. **think** — inspect what data/classes/properties exist, choose a query
2. **query** — run a constrained, parameterized read
3. **observe** — receive data plus counts, coverage, provenance, warnings
4. repeat or stop

This is a requirement on the **query contract and ontology metadata**, more than
on inference rules. The RDFS/OWL-lite rules should stay declarative; agent
policy (“what to query next”, retry policy, stop conditions) belongs in the
agent harness, not in the ontology.

### What the ontology should expose

Add an inspectable catalog (design decides exact attributes):

- classes: id, label, description, parent classes, counts
- properties: id, label, description, domain, range, value type,
  cardinality, whether multi-valued, whether queryable/sensitive
- query templates/safe operations for common patterns:
  - entity by id
  - entities by class
  - entities by linked term (company/role/field/location)
  - term descendants/ancestors (`broader`, subclass)
  - neighbors/evidence for an entity
  - counts/aggregates
- assertion metadata:
  - status (`:candidate`, `:approved`, `:corrected`, `:rejected`)
  - confidence
  - extractor/source/version and locator
  - validity/observed-at
- entity/term/evidence inverses already in the rules:
  - `entity-term` / `term-entity`
  - `entity-evidence` / evidence → entity
  - `channel-program`, `episode-program`
  - `job-field-closure`, `broader`, `subclass`

These are mostly **catalog/meta rules and query API contracts**, not new
inference semantics. The existing subclass, same-as, broader/narrower, and
entity-link rules remain the reasoning core.

### What the observation envelope should include

A query result should be self-describing:

- `:result/data`
- `:result/count` and `:result/truncated`
- `:result/cursor` / stable ordering for pagination
- `:result/as-of` or database snapshot
- `:result/coverage` (which sources/classes/date range were searched)
- `:result/warnings` (missing data, stale cache, unresolved refs)
- provenance/status/confidence per field or assertion
- whether a human correction is in effect

Without coverage and “no result vs. unknown vs. not authorized” distinctions,
an agent can wrongly infer absence. The observation envelope must make those
distinct.

### Human and agent boundaries

- The agent loop is **read-only** for the ontology/cache.
- Corrections, approvals, provisional-term promotion, and extractor
  quarantine are human actions.
- Agents may propose corrections or queries, but not silently mutate the
  approved cache.
- Agent session traces (queries, observations, decisions) should be stored
  separately from ontology facts; they are audit/telemetry, not truth.
- Sensitive fields need a visibility/redaction policy; the catalog should mark
  them rather than exposing everything by default.

### Do we need to change the ontology rules?

**Yes, modestly:**

- add a class/property catalog and queryability/sensitivity metadata
- add assertion status/provenance/confidence/validity to the graph model
- add inverse rules for entity/term/evidence where useful
- keep existing inference rules; do not encode think/observe policy as rules
- possibly materialize closure later for query latency, but only as a
  performance optimization

**No, for the control loop itself:**

- no `think`/`observe` predicates in the ontology
- no agent-specific retry/stop/branching rules in Datalog
- no arbitrary agent-generated Datalog writes

The design phase should define the catalog and result envelope precisely.

## arXiv filter intent

The filter is **include**, not exclude.

Primary criterion: the paper is about an **agentic knowledge-graph/ontology
pattern** — LLM agents or autonomous workflows that build, query, reason over,
or coordinate with a knowledge graph or ontology.

Secondary criterion: **reimplementation fit** with Cypher/Datalog. Papers that
use graph query languages, Datalog, rules, graph traversal, or comparable
declarative query models rank higher. Cypher/Datalog is the lens for
reimplementation; it is not necessarily a hard requirement if the agentic
KG/ontology pattern is important.

Exclude by default:

- generic ontology papers with no agentic/autonomous workflow
- generic Cypher/Datalog tutorials without a KG/ontology/agent pattern
- papers whose only relevance is a passing mention of one keyword

The exact predicate/scoring is a design decision. Edge cases should be
human-reviewable rather than silently included.

## Intent status

No open intent questions. The design phase must turn these decisions into:

- a concrete task manifest and directory layout
- a concrete output/assertion/entity sidecar schema
- a filter DSL for the arXiv include case
- a correction/review workflow
- a safe query DSL and observation envelope
- an audit namespace for agent runs/observations

## Future design questions

- Which local model(s) and serving stack; how model registry/versioning works.
- Auto-approval policy, confidence calibration, gold-set thresholds, and sample
  review rates.
- Embedding model/dimension/privacy and re-embedding lifecycle. Datalevin
  1.2.11 provides `:db.type/vec` and vector search on this host.
- Embedding model choice, dimensions, normalization, and privacy/redaction.
- Hybrid ranking/scoring policy and how approximate-nearest-neighbor results are
  reported in observations.

## Design scope

The design/spec covers:

- task manifest format
- script CLI and directory layout
- gws fetch/pagination/raw-MIME handling
- output JSON schema and `task_extract` naming
- filter DSL/EDN shape
- provenance fields
- import/upsert integration
- testing fixtures
- migration/promotion rules for task-specific entities
- safe query DSL/templates and their observation envelope
- separate agent-run/observation audit namespace
- catalog-level sensitivity/redaction policy
