# Backlog Triage

Companion to [Antigravity Port & Ontology Enforcement — Intent](antigravity-port-intent.md).

Status date: 2026-11-24. Planning document; not a commitment that every item
will be implemented.

## How to read this

- **P0** — blocks the ontology port or risks corrupting the graph/cache.
- **P1** — required for the first usable end-to-end port.
- **P2** — valuable, but can follow the first usable path.
- **P3** — cleanup/documentation; do when touching the area.
- **Decision** — `now`, `next`, `later`, `won't do`, or `needs decision`.

Effort: XS (<30 min), S (0.5–2 h), M (0.5–2 days), L (>2 days).

## Locked decisions

- Output root: `data/`, configurable.
- `email_schema.json` keys preserved; `sizeEstimate`/`snippet` nullable.
- DF: pooled across sources by default; optional per-source; newest-N with
  Message-ID tie-break; default sample 500; `:hash`/`:all` modes available.
- Classifier moves into `src/` and gets a `takeout` subcommand; bb script is a
  wrapper.
- `resources/labels-categorized.json` stays and becomes a label taxonomy graph.
- TV guide is in scope (later phase).
- DeepSeek only; no Gemini.
- Ontology graph is canonical; JSON cache is a materialized view.

## Already done

PR 1–5 delivered: hermetic tests/fixtures, API/parser/splitter fixes, streaming
ingest, nil/subject/stat/export fixes, CLI output contract, thread/query
projection performance, script path env vars, dependency cleanup, docs/changelog,
and the DeepSeek classifier with cache enforcement.

## Critical gws identity finding

Gmail API IDs are hex; Takeout `From_`/`X-GM-THRID` are decimal. The current
`update-message-ids` writes API hex thread IDs over decimal ones, which splits
threads. This must be fixed before gws enrichment.

See [GWS Enrichment](gws-enrichment.md).

| ID | Item | Why | Effort | Decision |
|----|------|-----|--------|----------|
| G1 | Canonical Gmail ID helpers (`hex->decimal`, `decimal->hex`, validation, IRIs) and canonical storage rule. | Stops thread/ID fragmentation. | S | **now** |
| G2 | Fix `update-message-ids`: normalize API hex, conflict checks, reject synthetic/dry-run IDs, migrate old hex IDs. | Prevents corrupting thread grouping. | S–M | **now** |
| G3 | Body enrichment provenance (`:email/body-source`, fetched-at), only fill blanks by default; `--force` explicit. | Avoid overwriting Takeout bodies silently. | S | **next** |
| G4 | Label refresh semantics: union by default, `--replace-labels`, label provenance. | Live labels are mutable; MBOX labels are snapshots. | S | **next** |
| G5 | Live raw ingest adapter (`messages.get format=raw` → MIME parser → upsert by Message-ID). | Needed for Gmail-only sources; local mode must still work without it. | M | **later** |

## P0 — ontology graph and enforcement

| ID | Item | Why it blocks | Effort | Decision |
|----|------|----------------|--------|----------|
| O1 | Define graph schema + EDN rule files (`ontology/schema.edn`, `ontology/rules.edn`): classes, parents, disjointness, term/form/label entities, RDFS + selected OWL 2 RL rules, validation queries. See [Ontology Reasoning](ontology-reasoning.md). | The graph is the new source of truth; everything else depends on it. | M–L | **now** |
| O2 | Extract shared ontology rules from `scripts/ontology_classifier.clj` into testable Clojure namespaces. | Candidate compiler, matcher, and classifier must not drift. | M | **now** |
| O3 | Automated tests for rules + graph validation: subclass/type closure, sameAs closure, broader/narrower/related, disjointness, functional properties, categories, aliases, allow/stop/DF, dual-key cache, cache/graph agreement. | The classifier currently has manual verification only. | M | **now** |
| O4 | Port `compile_raw_candidates.py` to a DB-first compiler using shared rules and deterministic DF. | Without candidates, the classifier has nothing new to classify. | M | **next** |
| O5 | Port local matcher (`extract_ontology_body`) + schema-valid structured export. | This is the actual enrichment output. | M | **next** |

## P1 — first usable end-to-end path

| ID | Item | Notes | Effort | Decision |
|----|------|-------|--------|----------|
| O6 | Classifier relocation: `src/` namespace + `takeout ontology classify`; bb wrapper; write graph then export cache. | Locked decision. | M | **next** |
| O7 | Portable source model for local DB mode (labels/from/to/subject/text/since/limit); legacy Gmail `:query` only for live mode. | Replaces Antigravity query-only config. | S–M | **next** |
| O8 | Cache/graph seed + migration command; canonical alias seed. | Equivalent to `populate_ontology_cache.py`, with audit source `seed`/`manual`. | S | **next** |
| O9 | Schema validation/resource for structured output. | Preserve keys; allow nulls. | S | **next** |
| O10 | End-to-end `takeout` command path: compile → classify → match → report. | One documented path. | S | **next** |
| O11 | YouTube ID extraction + oEmbed metadata cache. | TTL/concurrency policy. | S | **next** |
| O12 | Markdown generator with sensitive-data masking and global stats. | Port `generate_markdown.py`. | S | **next** |

## P2 — later

| ID | Item | Notes | Effort | Decision |
|----|------|-------|--------|----------|
| O13 | Label taxonomy graph from `resources/labels-categorized.json`; `label-ancestor` Datalog rules. | Keep JSON seed; optionally generate EDN. | S | **later** |
| O14 | TV-guide formatter. | Confirmed in scope; depends on structured JSON + YouTube. | M | **later** |
| O15 | Optional live `gws` source adapter. | See G5. | M | **later** |
| O16 | LinkedIn as source config. | Falls out of O7/O15. | S | **later** |
| O17 | Gold-set/local-model evaluation harness. | Metrics/gate already in intent. | M | **later** |
| O18 | Cache/graph reporting dashboard. | `--check` covers most; summary command later. | S | **later** |
| O19 | Turtle/RDF export (typed graph → `triple/3` view). | Optional interoperability, not needed for v1. | M | **later** |
| O21 | Materialize closure (`:inferred/*`) after profiling. | Query-time rules first; materialize only if closure queries are hot. | M | **later** |
| O22 | RDFS/OWL 2 RL conformance notes and rule coverage matrix. | Track which rules are implemented/tested; no full OWL DL claim. | S | **next** |
| O20 | Frequency + embedding hybrid for borderline DF. | Needs embeddings; optional. | L | **won't do for now** |

## Existing backlog carried forward

| ID | Item | Source | Effort | Decision |
|----|------|--------|--------|----------|
| T15 | Document `MboxIterator` envelope requirements and ~50 MB per-message cap. | Parse review | XS | **next** |
| T16 | Review `clean-body` header/boundary stripping; avoid deleting legitimate quoted content. | Parse review | M | **needs decision** |
| T17 | Re-ingest only adds labels; removed labels persist. Decide replace-set semantics or document. | Ingest review | M | **needs decision** |
| T24 | Document/decide the 10 K body truncation limit. | Data model | XS doc / L feature | **document now** |
| T26 | Extract `message/rfc822` forwarded content. | Parse review | M | **later** |
| T27 | Confirm/decode RFC 2047-encoded Gmail labels. | Parse review | S | **investigate** |
| B1 | `resources/labels-categorized.json` now has a purpose: label taxonomy graph (O13). | Cleanup | XS | **scheduled** |
| B2 | `gws` OAuth is not configured; live mode cannot run yet. | Setup | S manual | **operational** |
| B3 | Link intent/triage from docs. | Docs | XS | **done** |

## Recommended sequence

0. **Phase 0.5 (P0):** G1 canonical ID helpers, G2 update-message-ids
   normalization/conflict tests. Can run alongside Phase 1, but must precede any
   gws enrichment.
1. **Phase 1 (P0):** O1 graph schema/rules, O2 shared rules, O3 tests.
2. **Phase 2 (P0/P1):** O4 candidate compiler; O5 local matcher; O9 schema; O6
   classifier relocation.
3. **Phase 3 (P1):** O7 sources, O8 seed/migration, O10 end-to-end command.
4. **Phase 4 (P1):** O11 YouTube, O12 Markdown.
5. **Phase 5 (P2):** O13 label taxonomy, O14 TV guide, O15/O16 live Gmail and
   LinkedIn, O17 evaluation; G3/G4/G5 as gws work begins.
6. **Backlog cleanup:** T15/T24 docs; T16/T17 decisions; T26/T27 later.

## Decisions still open

1. Default DF sample size = 500? (proposed)
2. Category hierarchy: `company < organization`, plus `role`, `field`,
   `job-type`, `location`? (proposed)
3. Markdown before TV guide? (proposed)
4. `config.json` remains editable with EDN rule files, or migrate to
   `ontology/config.edn` and keep JSON compatibility? (proposal: support both,
   EDN canonical for new rules)
5. Turtle/RDF export later? (proposal: yes, later)

## Acceptance for the first usable milestone

- Graph validation rejects category conflicts, disjoint types, missing classes,
  incomplete key pairs, and cache/graph disagreement.
- Candidate compiler is deterministic and never classifies.
- Classifier sends only unseen terms and updates graph + cache atomically.
- Local matcher output validates against the schema and makes no network/model
  calls.
- Markdown output masks sensitive data.
- All new rules/compiler/matcher logic is covered by JVM tests.
