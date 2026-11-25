# Replayable gws Task Extraction — Design

Status: **design for review**. No implementation yet.
Intent: [Replayable gws Extraction Tasks — Intent](gws-task-extraction-intent.md).

## 1. Locked decisions

| Area | Decision |
|---|---|
| Output | Legacy-compatible email array + `task_extract`; optional entity sidecar |
| Packaging | One self-contained task script + manifest + fixtures under `scripts/tasks/<task>/` |
| DB flow | Fetch → task JSON → extract/filter → import |
| Derived entities | Task-scoped/staged until reviewed |
| Filter | arXiv: include agentic KG/ontology patterns; Cypher/Datalog is the reimplementation lens |
| LLM | Local deterministic by default; explicit opt-in with model/prompt recorded |
| Provenance | Reified field-value assertions with stable IDs |
| Corrections | Datalevin correction entities + EDN/JSON export; never overwritten by re-extraction |
| Correction scope | Task/entity fields first; imported email fields only if needed |
| Correction UX | `takeout corrections` + EDN review file, not raw transactions |
| Agent query | Constrained templates/safe DSL, no arbitrary Datalog |
| Agent trace | Separate audit namespace, not ontology facts |
| Coverage/as-of | Required on every query result |
| Sensitivity | Catalog flags + redaction defaults |
| Latency | Measure first; materialize only hot closure paths |

## 2. End-to-end flow

```text
task.edn + run.clj
       │
       ├─ gws fetch ──────────────► raw emails.json + run metadata
       │                                  │
       │                                  ▼
       └─ --from raw.json ────────► extract + filter
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
              emails.json          assertions.edn        entities.edn
              (importable)         (field claims)        (optional, staged)
                    │                     │                     │
                    ▼                     ▼                     ▼
             import-emails        assertions import      entity review/upsert
                    │                     │                     │
                    └──────────────► Datalevin ◄────────────────┘
                                          │
                              corrections / review / queries
```

Fetching and extraction are separable:

- `run.clj --fetch-only` writes the raw export.
- `run.clj --from raw-export.json --extract-only` re-runs extraction/filter
  without Gmail.
- The default run does fetch → extract → write outputs, but does **not**
  silently import into Datalevin.

## 3. Directory layout

```text
scripts/tasks/
  arxiv-agentic-kg/
    task.edn          ; manifest
    run.clj           ; thin Babashka wrapper
    README.md         ; human review notes
    fixtures/         ; sample raw/expected outputs
```

Shared pure code lives in `src/astanova/tasks/`:

```text
src/astanova/tasks/
  manifest.clj        ; read/validate task.edn
  fetch.clj           ; gws list/get, raw MIME, pagination
  extract.clj         ; shared extractors (regex, HTML, links)
  filter.clj          ; EDN filter DSL
  assertions.clj      ; assertion construction/IDs/merge
  output.clj          ; atomic JSON/EDN writers
  review.clj          ; review/diff helpers
```

Task-specific parsing stays in the task script or in a task-scoped namespace
when it is large enough to test. Shared helpers do not contain task-specific
keywords.

Tests:

```text
test/astanova/tasks/...
test/fixtures/tasks/<task>/...
```

## 4. Task manifest

`task.edn` is the human-reviewable declaration:

```edn
{:task/id "arxiv-agentic-kg"
 :task/version 1
 :task/label "arXiv agentic KG/ontology papers"

 :fetch
 {:account "me"
  :query "from:no-reply@arxiv.org"
  :since "2026-01-01"
  :until "2026-11-25"
  :max-results 500
  :page-size 100
  :format :raw
  :delay-ms 250}

 :extract
 {:extractor/id "arxiv-paper-v1"
  :extractor/version 1
  :fields
  [{:field/id :paper/title
    :field/type :string
    :field/required true
    :field/method :html-meta
    :field/sensitive false}
   {:field/id :paper/abstract
    :field/type :string
    :field/required true
    :field/method :html-section
    :field/sensitive false}
   {:field/id :paper/url
    :field/type :string
    :field/required true
    :field/method :link
    :field/multi true
    :field/sensitive false}
   {:field/id :paper/authors
    :field/type :string
    :field/multi true
    :field/method :html-meta
    :field/sensitive false}
   {:field/id :paper/categories
    :field/type :string
    :field/multi true
    :field/method :html-meta
    :field/sensitive false}]}

 :filter
 {:mode :include
  :rules
  [{:rule/id :agentic
    :field [:paper/title :paper/abstract]
    :op :matches-any
    :terms ["agentic" "LLM agent" "multi-agent" "autonomous agent"
            "agent workflow" "agentic RAG"]
    :weight 3}
   {:rule/id :kg-ontology
    :field [:paper/title :paper/abstract]
    :op :matches-any
    :terms ["knowledge graph" "knowledge-graph" "ontology"
            "ontologies" "semantic graph"]
    :weight 3}
   {:rule/id :declarative-query
    :field [:paper/title :paper/abstract]
    :op :matches-any
    :terms ["Cypher" "Datalog" "SPARQL" "graph query" "rule engine"]
    :weight 1}]
  :include-if {:all [:agentic :kg-ontology]}
  :min-score 6
  :review-band 1}

 :output
 {:dir "data/tasks/arxiv-agentic-kg"
  :emails "emails.json"
  :assertions "assertions.edn"
  :entities "entities.edn"
  :run "run.edn"}

 :import
 {:emails true
  :assertions false
  :entities false}

 :llm
 {:enabled false
  :provider nil
  :model nil
  :prompt-id nil}

 :sensitivity
 {:default :redact
  :fields {:paper/abstract :visible
           :paper/title :visible}}}
```

A manifest version bump is required when the query, field set, filter semantics,
or extractor behavior changes materially. The design keeps both
`:task/version` and `:extractor/version`; assertions record both.

## 5. Script CLI

`scripts/tasks/arxiv-agentic-kg/run.clj` is a thin wrapper:

```bash
bb scripts/tasks/arxiv-agentic-kg/run.clj \
  --from raw-emails.json \
  --out-dir data/tasks/arxiv-agentic-kg \
  --extract-only
```

Options:

| Option | Meaning |
|---|---|
| `--from PATH` | use an existing raw export; skip fetch |
| `--out-dir DIR` | override manifest output dir |
| `--fetch-only` | fetch and write raw export; no extraction |
| `--extract-only` | extract/filter from `--from`; no Gmail |
| `--dry-run` | show plan/counts; no writes |
| `--limit N` | cap messages for this run |
| `--since DATE` / `--until DATE` | override the manifest window |
| `--force` | overwrite task outputs atomically |
| `--review` | write a review file instead of marking assertions approved |
| `--verbose` | show per-message extraction/filter decisions |

The script does not mutate Datalevin directly. Import is a separate explicit
step.

## 6. Fetch stage

`astanova.tasks.fetch` uses `gws` read-only operations:

1. `gmail users messages list` with the configured query, pagination, and
   limits.
2. `gmail users messages get` with `format=raw` (or `full` when raw is
   unavailable); decode base64url and reuse the MIME parser.
3. `gmail users labels list` to resolve label names.
4. Canonicalize Gmail IDs to decimal.
5. Write a raw email export with legacy-compatible fields.

Fetch outputs:

```text
raw-emails.json       ; array of email records
raw-run.edn           ; query, account, window, counts, errors, timestamps
```

The raw export is a replay input. It is not imported automatically; the task
can be re-run from it without Gmail.

Failure behavior:

- auth failure, rate limit, not-found, and partial page failures are distinct
- retries/backoff are bounded
- a partial run writes a run record with `:run/status :partial`
- no destructive Gmail operations
- no credentials or tokens in output

## 7. Extraction stage

Extraction produces:

- `task_extract` on each email record
- field-value assertions
- optional task-scoped entities

### Email output

The email file remains a JSON array. Core fields stay legacy-compatible:

```json
{
  "messageId": "<...@arxiv.org>",
  "gmailId": "186...",
  "threadId": "186...",
  "subject": "...",
  "from": "...",
  "to": "...",
  "internalDate": 178...,
  "internalDateIso": "...",
  "labels": ["..."],
  "body_truncated": "...",
  "task_extract": {
    "task/id": "arxiv-agentic-kg",
    "task/version": 1,
    "extractor/id": "arxiv-paper-v1",
    "filter": {
      "decision": "include",
      "score": 7,
      "reasons": ["agentic", "kg-ontology", "declarative-query"]
    },
    "fields": {
      "paper/title": "...",
      "paper/abstract": "...",
      "paper/url": ["https://arxiv.org/abs/..."],
      "paper/authors": ["..."],
      "paper/categories": ["cs.AI"]
    }
  }
}
```

`task_extract` is the only task-specific email extension. It must not alter the
meaning of core email fields.

Detailed lifecycle, review, correction, and re-extraction behavior:
[Assertion Workflow](assertion-workflow.md).

### Assertions

Assertions are reified field-value claims. They are the authoritative
provenance layer, and the basis for field-level corrections.

```edn
{:assertion/id "assertion:<sha256>"
 :assertion/subject-type :email
 :assertion/subject-id "<...@arxiv.org>"
 :assertion/field :paper/title
 :assertion/raw-value "Original title"
 :assertion/value "Normalized title"
 :assertion/value-type :string
 :assertion/multi false
 :assertion/status :candidate
 :assertion/extractor-id "arxiv-paper-v1"
 :assertion/extractor-version 1
 :assertion/task-id "arxiv-agentic-kg"
 :assertion/task-version 1
 :assertion/method :html-meta
 :assertion/confidence 0.9
 :assertion/locator {:mime "text/html" :path "meta[name=citation_title]"}
 :assertion/snippet "Original title"
 :assertion/observed-at #inst "2026-11-25T00:00:00Z"
 :assertion/llm nil}
```

Assertion ID derivation:

```text
sha256(subject-type | subject-id | field | extractor-id | extractor-version |
       locator | raw-value)
```

- same input re-run → same assertion ID (idempotent)
- changed raw value or locator → new assertion
- multi-valued fields produce one assertion per value
- LLM-produced assertions include `:assertion/llm
  {:provider ... :model ... :prompt-id ...}`; the rest of the shape is identical

Assertions are written to `assertions.edn` as a vector and imported separately
from emails. The import requires the subject email/entity to exist.

The `:assertion` record is the current projection; every status transition and
correction also appends an immutable `:assertion-revision/*` record. Automation
consumes revisions, not mutable status fields. See
[Assertion Workflow](assertion-workflow.md).

### Entities

Task-scoped entities (e.g. `:paper`) are written to `entities.edn` with the
same entity/evidence conventions as the existing entity layer. They are
**staged**, not automatically promoted into the shared ontology. A review step
promotes selected entities/terms.

## 8. Filter DSL

The filter is declarative EDN. It does not execute arbitrary code.

Supported operators:

- `:matches-any` / `:matches-all` / `:not-matches`
- `:equals` / `:in`
- `:present` / `:absent`
- `:min-length`
- `:and` / `:or` / `:not` composition

Example:

```edn
{:filter/mode :include
 :filter/rules
 [{:rule/id :agentic
   :field [:paper/title :paper/abstract]
   :op :matches-any
   :terms ["agentic" "LLM agent" "multi-agent" "autonomous agent"]
   :normalize :case-fold
   :weight 3}
  {:rule/id :kg-ontology
   :field [:paper/title :paper/abstract]
   :op :matches-any
   :terms ["knowledge graph" "ontology" "semantic graph"]
   :normalize :case-fold
   :weight 3}
  {:rule/id :declarative-query
   :field [:paper/title :paper/abstract]
   :op :matches-any
   :terms ["Cypher" "Datalog" "SPARQL" "graph query"]
   :normalize :case-fold
   :weight 1}]
 :filter/include-if {:all [:agentic :kg-ontology]}
 :filter/min-score 6
 :filter/review-band 1}
```

Semantics:

- `:mode :include` — keep matching records; `:mode :exclude` — drop matching
- `include-if` is evaluated before scoring
- score is the sum of matched rule weights
- `min-score` accepts; `review-band` marks borderline records for human review
- every decision records matched rule IDs and a short reason
- filter evaluation is deterministic and does not call an LLM unless
  `:llm/enabled` is explicitly true

For arXiv:

- include agentic KG/ontology patterns
- score/boost Cypher/Datalog/declarative-query relevance
- exclude generic ontology papers without an agentic angle
- exclude generic Cypher/Datalog papers without a KG/ontology/agent pattern
- borderline cases go to human review

## 9. Corrections and review

### Correction entity

```edn
{:correction/id "correction:<sha256>"
 :correction/assertion-id "assertion:..."
 :correction/subject-type :email
 :correction/subject-id "<...@arxiv.org>"
 :correction/field :paper/title
 :correction/old-value "..."
 :correction/new-value "..."
 :correction/reason "extractor picked the email subject, not the paper title"
 :correction/reviewer "human"
 :correction/status :active
 :correction/scope :single
 :correction/extractor-id "arxiv-paper-v1"
 :correction/extractor-version 1
 :correction/created-at #inst "..."}
```

Effective value resolution:

1. active correction for the assertion/field → correction value
2. otherwise approved assertion
3. otherwise candidate assertion (visible as unapproved)
4. otherwise raw email field

The original assertion is never deleted.

### Review file

```edn
{:review/task "arxiv-agentic-kg"
 :review/generated-at #inst "..."
 :items
 [{:assertion/id "assertion:..."
   :action :correct
   :new-value "..."
   :reason "..."}
  {:assertion/id "assertion:..."
   :action :approve}
  {:assertion/id "assertion:..."
   :action :reject
   :reason "..."}]}
```

The human edits the review file; `takeout corrections import` applies it
transactionally.

### Re-extraction behavior

- emit a diff: new / unchanged / changed / conflicting / corrected
- an existing active correction is never overwritten
- if a new extraction disagrees with a correction, the new assertion is marked
  `:candidate`/`:conflicted` and requires review
- bulk corrections can target all active assertions from an extractor/version
  and field

### Commands

```bash
./takeout assertions review --task arxiv-agentic-kg --status candidate
./takeout corrections add --assertion assertion:... --value "..." --reason "..."
./takeout corrections export --out corrections.edn
./takeout corrections import --from corrections.edn
./takeout corrections list --extractor arxiv-paper-v1 --field :paper/title
```

Exact flags are implementation details; the intent is no raw Datalevin
transactions for reviewers.

## 10. Safe query DSL and observation

Agent query surface is a named set of operations, not raw Datalog:

```edn
{:op :entity/by-term
 :args {:term-id "term:knowledge-graph"
        :class :job-posting
        :limit 50
        :cursor "..."}}
```

Proposed operations:

- `:entity/by-id`
- `:entity/by-class`
- `:entity/by-term`
- `:term/ancestors` / `:term/descendants`
- `:entity/neighbors`
- `:entity/evidence`
- `:count`
- `:search` (text/term)

Every result is an observation envelope:

```edn
{:observation/id "..."
 :query/id "..."
 :query/op :entity/by-term
 :result/data [...]
 :result/count 50
 :result/truncated true
 :result/cursor "..."
 :result/as-of #inst "..."
 :result/coverage {:sources ["legacy-json" "gws"]
                   :classes [:job-posting]
                   :date-range {:from #inst "..." :to #inst "..."}}
 :result/warnings [{:type :stale-cache :message "..."}]
 :result/fields [{:field :job/company
                  :status :corrected
                  :assertion-id "assertion:..."
                  :correction-id "correction:..."}]
 :result/redactions [{:field :email/body :reason :sensitive}]}
```

Rules:

- stable ordering + cursor for pagination
- `as-of` is mandatory
- coverage is mandatory
- no-result vs unknown vs unauthorized vs truncated must be distinguishable
- field status/provenance travels with the data
- sensitivity flags redact before response

## 11. Agent audit namespace

Separate from ontology facts:

```text
:agent-run/id
:agent-run/started-at
:agent-run/ended-at
:agent-run/purpose
:agent-run/query-count
:observation/id
:observation/run-id
:observation/query
:observation/result-summary
:observation/created-at
```

Audit records are append-only. They do not participate in inference and are
not exposed as ontology classes/terms. Retention/export policy is a later
design detail.

## 12. Datalevin schema additions

Namespaces:

- `:task/*` — task run metadata and coverage proof
- `:assertion/*` — current field-value claims
- `:assertion-revision/*` — append-only assertion history
- `:correction/*` — human corrections, including `:retired`
- `:correction-proposal/*` — LLM/agent proposals awaiting human review
- `:agent-run/*`, `:observation/*` — audit

Uniques:

- `:task-run/id`
- `:assertion/id`
- `:assertion-revision/id`
- `:correction/id`
- `:correction-proposal/id`
- `:agent-run/id`
- `:observation/id`

Assertions link to email/entity subjects and evidence. Corrections link to
assertions. Ontology cache remains read-only for task scripts and agent loops.

## 13. Import flow

```bash
# 1. Fetch + extract/filter (no DB writes)
bb scripts/tasks/arxiv-agentic-kg/run.clj

# 2. Import emails (legacy-compatible array)
./takeout import-emails \
  --from data/tasks/arxiv-agentic-kg/emails.json \
  --source task-arxiv-agentic-kg

# 3. Import assertions as candidates (subject email must exist)
./takeout assertions import \
  --from data/tasks/arxiv-agentic-kg/assertions.edn

# 4. Human reviews/corrects
./takeout assertions review --task arxiv-agentic-kg
./takeout corrections import --from data/tasks/arxiv-agentic-kg/review.edn

# 5. Optional: review and promote task-scoped entities
./takeout upsert-entities --from data/tasks/arxiv-agentic-kg/entities.edn
```

Task runs do not auto-approve assertions or promote entities.

## 14. Error handling and atomicity

- Validate `task.edn` before fetching.
- Write outputs to temp files, then rename atomically.
- A failed run leaves the previous output intact and records a failed run.
- Partial fetch is explicit (`:run/status :partial`).
- Duplicate message IDs are deduplicated within a task run.
- Re-running with `--force` overwrites outputs; without it, existing outputs are
  preserved and a warning is printed.
- No task output contains credentials.

## 15. Tests

- manifest schema/defaults/versioning
- gws fixture → raw export, canonical IDs, labels
- MIME/HTML extraction, multi-valued fields
- filter include/exclude, score, review band, reasons
- assertion IDs, idempotency, multi-value assertions
- correction precedence; re-extraction diff; no overwrite
- email output imports cleanly
- observation envelope: coverage/as-of/cursor/redaction
- safe query DSL rejects unknown ops/unsafe args
- task replay from raw JSON without Gmail

## 16. Phases

1. **T1** manifest schema, output/run layout, assertion model, correction
   schema, atomic writers.
2. **T2** arXiv task: gws fetch, paper extraction, include filter, fixtures.
3. **T3** assertion import/review/correction commands and diff behavior.
4. **T4** safe query DSL + observation envelope + agent audit namespace.
5. **T5** docs/CLI examples and end-to-end replay test.

## 17. Local model extractors and automation

### Extractor interface

Each field can choose a backend:

```edn
{:field/id :paper/abstract
 :field/backend :heuristic       ; :heuristic | :local-model | :hybrid
 :field/extractor-id "arxiv-paper-v1"
 :field/extractor-version 1}
```

A local-model extractor records:

```edn
{:extractor/type :local-model
 :extractor/id "arxiv-abstract-lfm"
 :extractor/version 1
 :extractor/model {:provider :local
                   :id "LFM-..."
                   :quantization "..."
                   :context-window 32768}
 :extractor/prompt-id "arxiv-abstract-v1"
 :extractor/params {:temperature 0.0
                    :seed 0
                    :max-tokens 512}}
```

Assertion provenance carries the same model/prompt/params and a response hash.
The output remains a `:candidate` assertion; local models do not get a
different truth status.

Caching key:

```text
sha256(source-text | model-id | model-version | prompt-id | params)
```

Replay uses the cache when present; `--refresh-model` bypasses it.

### Automation levels

```text
L0  human reviews every assertion
L1  human reviews low-confidence/conflicted subset
L2  auto-approve calibrated high-confidence values with sampled review
L3  fully autonomous (not planned)
```

Automation policy is explicit in the task manifest:

```edn
:automation
{:mode :L1
 :auto-approve
 {:min-confidence 0.95
  :max-per-run 100
  :require-method :local-model
  :sample-rate 0.02}
 :gold-set "test/fixtures/tasks/arxiv-agentic-kg/gold.edn"}
```

Rules:

- default is L0/manual
- an extractor/model/field combination must pass a gold-set evaluation before
  any auto-approval
- every auto-approval is a revision with `:actor :system` and policy id
- a human can revoke/retire auto-approved assertions
- auto-approval never overrides a human correction
- automation cannot approve sensitive fields unless explicitly allowed

## 18. Embeddings and semantic search

Embeddings are a derived semantic index, not ontology facts:

```edn
{:embedding/id "embedding:<sha256>"
 :embedding/subject-type :email       ; :email | :entity | :term | :assertion
 :embedding/subject-id "<...@arxiv.org>"
 :embedding/source-field :email/body-truncated
 :embedding/source-hash "sha256:..."
 :embedding/model-id "lfm-embed..."
 :embedding/model-version 1
 :embedding/dim 1024
 :embedding/normalized true
 :embedding/vector [...]               ; exact DB type depends on Datalevin version
 :embedding/status :current            ; :current | :stale
 :embedding/created-at #inst "..."}
```

Lifecycle:

- source change → embedding `:stale`; re-embed creates a new version
- model change → new embedding version; don't overwrite old vectors
- `:embedding/status :current` pointer per subject/field/model
- embeddings are excluded from ontology inference and assertions

**Datalevin dependency:** Datalevin 1.2.11 is installed and its vector
operations were verified on this Linux x86_64 host. Use `:db.type/vec` for a
Datalog vector attribute and `:vector-opts {:dimensions ...}` for the index.
If a future runtime loses that support, use a sidecar index or upgrade
deliberately.

### Semantic query operation

Add to the safe DSL:

```edn
{:op :search/semantic
 :args {:query "agentic knowledge graph ontology"
        :class :paper
        :top-k 25
        :min-score 0.7
        :hybrid {:lexical-weight 0.4 :vector-weight 0.6}}}
```

Observation additions:

```edn
{:result/ranking {:model-id "lfm-embed..."
                  :model-version 1
                  :metric :cosine
                  :approximate true
                  :hybrid true}
 :result/warnings [{:type :approximate-neighbors}]}
```

Rules:

- hybrid lexical+vector is preferred
- approximate search must be labeled as approximate
- coverage/as-of still required
- embeddings of sensitive fields inherit sensitivity/redaction
- agent loops may use semantic search for recall, never as proof of truth
- no agent writes to embedding tables

### Embedding-assisted review

Embeddings may help:

- cluster similar wrong values for bulk proposals
- retrieve evidence for a correction proposal
- deduplicate near-identical assertions/entities
- prioritize review by similarity to known corrections

They do not auto-approve assertions.

## 19. Open design details

- coverage proof for Gmail query/window equivalence and deletion semantics
- entity promotion workflow: entity + approved assertions together, or separate
- LLM correction-proposal review UX/thresholds
- query cursor encoding
- audit retention/export
- sensitivity catalog defaults
- whether LLM extraction is field-level only or whole-record
- whether task entity sidecars are imported automatically after review or remain
  a separate `upsert-entities` step
- Local-model serving/registry and prompt versioning
- auto-approval policy and gold-set thresholds
- embedding model/dimension/privacy choices
- hybrid ranking/scoring and approximate-neighbor reporting
