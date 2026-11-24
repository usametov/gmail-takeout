# Antigravity Port & Ontology Enforcement — Intent

Status: **intent / design**, no implementation in this change.
Companion: [Backlog Triage](backlog-triage.md).

## Why

`gmail-takeout` already has:

- MBOX ingestion into Datalevin (`:email/*`)
- query/thread/content tooling
- a DeepSeek ontology classifier and JSON cache (`ontology/`)

`antigravity-gmail-project` has the rest of the pipeline:

- candidate compilation + document-frequency analysis
- local ontology matching (`body_extract`)
- structured per-email JSON matching `email_schema.json`
- YouTube oEmbed metadata + disk cache
- Markdown digest and TV-guide generation
- config-driven Gmail sources
- Gemini-era classification (superseded by the DeepSeek classifier)

The goal is one **local, deterministic, DB-first pipeline** that enriches email
with a stable ontology, and enforces the ontology rules centrally.

## Locked decisions

| Decision | Choice |
|---|---|
| Output root | `data/` by default, configurable with `--out-dir` / `TAKEOUT_DATA_DIR` |
| Structured schema | Preserve `email_schema.json` keys exactly; allow `null` for `sizeEstimate`/`snippet` |
| DF scope | Pooled across selected sources by default; optional per-source |
| DF sampling | Deterministic; newest-N by default (details below) |
| Classifier location | Move into `src/` and expose a `takeout` subcommand; keep `scripts/ontology_classifier.clj` as a thin wrapper |
| `resources/labels-categorized.json` | Keep; load it as a separate label taxonomy graph |
| TV guide | In scope (later phase) |
| Provider | DeepSeek/default OpenAI-compatible; no Gemini |
| Source of truth | Ontology graph in Datalevin; JSON cache is a materialized compatibility view |
| Formalism | RDFS core + selected OWL 2 RL rules; typed graph, closed-world integrity; OWL-Lite is not the target |
| Gmail identity | Canonical decimal strings; API hex converted at the gws boundary |
| gws role | Email enrichment only (IDs, bodies, labels, live sources); never ontology classification |

Reasoning formalism and rule scope: [Ontology Reasoning](ontology-reasoning.md).
gws integration and identity rules: [GWS Enrichment](gws-enrichment.md).

## Core architecture

### 1. DB-first, Gmail-live optional

The `takeout` Datalevin database is the email source of truth.

- **Local mode (default):** candidate compilation and local matching query
  `:email/*`/`:content/*`.
- **Live mode (optional):** a `gws` adapter fetches by Gmail query for sources
  absent from Takeout, then normalizes to the same internal email map.

Local mode must work with no `gws` and no network.

### 2. Ontology graph + cache

Introduce a typed graph in Datalevin (RDF-like, not generic triples) as the
canonical ontology. The existing JSON cache becomes a **materialized view** for
fast local matching and Antigravity compatibility.

Graph entities:

| Entity | Attributes |
|---|---|
| class | `:class/id` (unique keyword), `:class/label`, `:class/parent` (ref, many), `:class/disjoint-with` (ref, many), `:class/status` |
| term | `:term/id` (unique), `:term/normalized` (canonical string), `:term/category` (ref class, one), `:term/same-as` (ref, many), `:term/broader`/`:narrower` (ref, many), `:term/status`, `:term/source`, `:term/confidence` |
| form | `:form/raw` (unique string), `:form/term` (ref), used for case/spelling variants |
| label | `:label/name` (unique), `:label/parent` (ref), `:label/category` (ref), `:label/source` |

The graph stores asserted facts; derived facts (ancestor classes, same-as
closure, inherited category) are produced by Datalog rules. The JSON cache is
exported from the graph with exact + lowercase keys and the existing
`{"category", "normalized"}` value shape.

### 3. Shared rules module

Extract the rules currently inside the bb classifier into pure, testable
Clojure namespaces used by:

- classifier
- candidate compiler
- local matcher (cache/alias validation)
- graph validation/cache export

Shared rules:

- category enum + normalization
- `normalization-key` / preferred normalized spelling
- canonical aliases
- allow/stop/standalone/DF noise rules
- cache value validation
- graph validation queries

### 4. LLM boundary

- Only classification calls DeepSeek.
- Candidate compilation is deterministic.
- Local matching is a pure cache lookup.
- A malformed or conflicting graph/cache fails the pipeline.
- Confidence/source/model live in graph metadata + audit log, not in the cache
  value shape.

### 5. Output compatibility

Structured output remains compatible with `email_schema.json`; missing DB-only
fields are `null`. `body_truncated` defaults to 2000 characters for schema
compatibility (the DB stores up to 10K), configurable.

## RDF-like ontology via Datalog

### What “RDF-like” means here

A typed property graph with RDFS/OWL-lite semantics:

- class hierarchy (`rdfs:subClassOf`)
- instance typing (`rdf:type`)
- `sameAs`/canonical equivalence
- SKOS-style `broader`/`narrower`
- disjoint classes
- domain/range validation
- transitive closure via Datalog

It is **not** full OWL DL: no open-world reasoning, property chains, cardinality
restrictions, or automatic RDF/Turtle serialization initially. A Turtle export
can be added later.

### Rules and schema as EDN

Keep a human-reviewable EDN pair:

```
ontology/
  schema.edn   ; classes, parents, disjointness, properties
  rules.edn    ; Datalog rules as EDN vectors + validation queries
  config.edn   ; allow/stop/standalone/DF/aliases (config.json still read)
```

Example `schema.edn`:

```edn
{:version 1
 :classes
 {:organizations {:label "Organizations"}
  :companies     {:label "Companies" :parents [:organizations]}
  :roles         {:label "Roles"}
  :fields        {:label "Fields"}
  :job_types     {:label "Job Types"}
  :locations     {:label "Locations"}}
 :disjoint #{[:companies :roles] [:companies :fields] [:roles :fields] ...}
 :properties
 {:term/category {:domain :term :range :class :cardinality :one}
  :term/same-as  {:domain :term :range :term}
  :label/parent  {:domain :label :range :label}}}
```

Example `rules.edn` (Datalog rules are data and can be passed to `d/q` as `%`):

```clojure
[[(subclass ?c ?p) [?c :class/parent ?p]]
 [(subclass ?c ?p) [?c :class/parent ?x] (subclass ?x ?p)]
 [(term-type ?t ?c) [?t :term/category ?c]]
 [(term-type ?t ?anc) (term-type ?t ?c) (subclass ?c ?anc)]
 [(label-ancestor ?l ?p) [?l :label/parent ?p]]
 [(label-ancestor ?l ?p) [?l :label/parent ?x] (label-ancestor ?x ?p)]]
```

This has been verified against Datalevin: recursive `ancestor` rules work when
using entity ids (or correctly registered lookup refs). Datalevin does not
reject invalid writes automatically, so validation is a pipeline step, not a
database constraint.

### Validation queries

Run these before committing a graph/cache update:

1. every term has exactly one asserted category
2. every category is in the configured enum
3. no term has two disjoint asserted categories
4. every normalized form maps to one category
5. no alias/same-as conflict changes a category
6. no term points at a missing class
7. every allow-listed term resolves to a cache/graph entry
8. cache key pairs (exact + lowercase) are complete
9. every cache entry agrees with the graph

Validation failures abort the update and export no cache.

### Cache/graph relationship

- Classifier writes graph facts + audit; then exports cache atomically.
- `--check` validates cache, graph, and consistency between them.
- Local matcher reads only the cache (fast, no DB dependency) after a startup
  validation; it never writes the graph.
- A cache entry not present in the graph is a hard error once the graph is the
  source of truth.

## Label taxonomy

`resources/labels-categorized.json` is a Gmail label hierarchy, not the entity
ontology. Keep it and load it as a separate label graph:

```edn
{:label/name "ai"        :label/category :ai-ml}
{:label/name "ai/agents" :label/parent "ai" :label/category :ai-ml}
```

- Top-level JSON keys become label categories.
- Slash paths imply `:label/parent`.
- Datalog `label-ancestor` rules can answer “all labels under `ai`”.
- The JSON remains the editable seed; optionally generate
  `ontology/labels.edn` for review.

## DF sampling (clarification)

DF is **not** “the newest N terms”. It is the fraction of sampled emails that
contain a candidate term:

```
DF(term) = documents containing term / sampled documents
```

Sampling policy:

1. Build the pooled set of emails from the selected sources.
2. Deduplicate by `:email/id`.
3. Sort by `:email/date` descending, then `:email/id` ascending.
4. Take `df_sample_size` (default **500**, configurable; Antigravity used 50).
5. Count each candidate at most once per document.
6. `DF >= df_threshold` ⇒ candidate becomes a DF stop unless allow-listed.

Recording in `raw_candidates`/report: sample size, mode, cutoff date, and a hash
of the selected Message-IDs, so a run is reproducible.

Modes (configurable):

- `:newest` (default) — current vocabulary; deterministic
- `:hash` — stable pseudo-random across history; less recency bias
- `:all` — no sampling; expensive

Per-source quotas are optional; default pooled. Newest sampling can over-represent
current job-market boilerplate and under-represent older vocabulary; use `:hash`
or `:all` when auditing historical coverage.

## GWS enrichment

`gws` is an **email enrichment adapter**, not part of ontology reasoning:

- maps Message-IDs to Gmail IDs and thread IDs
- fills missing bodies
- resolves live label names
- ingests live Gmail sources

It writes email facts only. Candidate compilation, classification, and local
matching still run against the DB/cache.

The critical identity rule is in [GWS Enrichment](gws-enrichment.md):

- Gmail API IDs are hex (`19f37d9602d2c400`).
- Takeout `From_`/`X-GM-THRID` are decimal (`1869976353534821376`).
- Canonical storage is decimal; convert at the API boundary with `BigInteger`.
- Never overwrite an existing decimal thread with an API hex value.
- Dry-run/synthetic IDs must never be applied.

## Port inventory

| Antigravity | Target | Notes |
|---|---|---|
| `compile_raw_candidates.py` | candidate compiler | DB-first, shared rules, deterministic DF |
| `fetch_emails.py` | local matcher + JSON exporter | DB source, no per-email model |
| `fetch_linkedin_emails.py` | generic source config | one code path |
| `classify_candidates_llm.py` | existing DeepSeek classifier | move to `src/` + CLI |
| `populate_ontology_cache.py` | graph/cache seed command | audit source `seed` |
| `email_schema.json` | schema validation | preserve keys, allow nulls |
| `generate_markdown.py` | Markdown generator | masking + stats + feed |
| `generate_tv_guide.py` | TV-guide generator | in scope, later phase |
| `config.json` | config loader | JSON + EDN; portable source spec |
| `youtube_cache.json` | YouTube oEmbed cache | TTL/refresh |
| `ontology_cache.json` | cache export | dual keys, value shape unchanged |
| `.agents` rules | intent/checklists | process only |

## Pipeline phases

### Phase 0.5 — Gmail identity normalization
- Canonical decimal IDs + `astanova.gmail-id` helpers.
- Fix `update-message-ids` conflict/dry-run validation before any gws run.
- Add ID round-trip/conflict/migration tests.
- This can run alongside Phase 1, but must be done before gws enrichment.

### Phase 1 — Ontology graph + shared rules + tests
- Implement the RDFS/OWL 2 RL subset in [Ontology Reasoning](ontology-reasoning.md).
- `schema.edn`/`rules.edn` loader.
- Graph schema and validation queries.
- Extract classifier rules into shared namespaces.
- Golden tests: categories, normalization, aliases, allow/stop/DF, cache/graph
  consistency.

### Phase 2 — Classifier relocation
- Move classifier into `src/`; add `takeout ontology classify`.
- Classifier writes graph + exports cache atomically.
- Keep `scripts/ontology_classifier.clj` as a thin wrapper.
- No behavior change for existing cache contract.

### Phase 3 — Candidate compiler
- Query configured DB sources; deterministic sample; shared noise rules.
- Write `raw_candidates.json` (or `.edn`) + update `auto_detected_df_stops`.
- Never classify, never call an LLM.

### Phase 4 — Local matcher + structured export
- Port proper-noun matching, exact/lower cache lookup, longest sub-phrase,
  links, YouTube IDs.
- Validate against `email_schema.json`.
- No network/model.

### Phase 5 — YouTube + Markdown + TV guide
- oEmbed cache and concurrency.
- Markdown masking/stats/feed.
- TV-guide formatter.

### Phase 6 — Live Gmail / LinkedIn / evaluation
- Optional `gws` source adapter.
- LinkedIn as source config.
- Gold-set/local-model evaluation harness.

## Acceptance criteria

- Graph validation rejects category conflicts, disjoint types, missing classes,
  incomplete key pairs, and cache/graph disagreement.
- Candidate compiler output is deterministic and never classifies.
- Classifier uses only unseen terms and updates graph + cache atomically.
- Local matcher output validates against the schema and makes no network/model
  calls.
- Markdown and TV-guide outputs mask sensitive data.
- End-to-end DB → candidates → classify → match → report is documented.
- Tests cover rules, graph validation, compiler, matcher, schema, and export.

## Open decisions

1. Default DF sample size: proposal **500**, newest-N.
2. Do we need a Turtle/RDF export, or is the typed graph + JSON cache enough for
   now? Proposal: later.
3. Which category hierarchy? Proposal:
   `companies < organizations`, plus `roles`, `fields`, `job_types`, `locations`.
4. TV-guide priority relative to Markdown/YouTube (proposal: after Markdown).
5. Whether `config.json` remains the editable file or migrates to
   `ontology/config.edn` with JSON compatibility. Proposal: support both, EDN
   canonical for new rule files.
