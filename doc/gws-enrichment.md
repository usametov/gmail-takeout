# GWS Enrichment in the Ontology Pipeline

Status: **G1/G2 implemented; G3+ design**, companion to
[Legacy Gmail Pipeline Port Intent](legacy-pipeline-port-intent.md) and
[Ontology Reasoning](ontology-reasoning.md).

## Role of gws

`gws` is an **email enrichment adapter**, not ontology reasoning.

It supplies:

- canonical Gmail message/thread identities
- missing or richer bodies
- live Gmail label names
- live Gmail source listings (`:query` sources)

The ontology graph, candidate compiler, classifier, and local matcher remain
DB/cache-first. `gws` never classifies a term and never writes the ontology
cache.

## Critical identity finding

Gmail API IDs and Takeout IDs use different encodings:

- Gmail API `id` / `threadId`: **hex strings**, e.g. `19f37d9602d2c400`
- Takeout `From_` / `X-GM-THRID`: **decimal strings**, e.g.
  `1869976353534821376`

They are the same 64-bit unsigned value:

```
0x19f37d9602d2c400 = 1869976353534821376
0x19f37d88241fb475 = 1869976293963969653
```

The current `update-message-ids` command writes the API hex `thread-id`
directly over the existing decimal `X-GM-THRID`. That splits one Gmail thread
into two IDs depending on whether a message came from Takeout or gws. It also
mixes hex/decimal `:email/gmail-id` values across the DB.

### Canonical rule

- **Canonical storage:** decimal string, matching Takeout/`X-GM-*`.
- **API boundary:** convert hex → decimal before writing.
- **API calls:** convert decimal → hex only when calling `gws`.
- Use `BigInteger`, not `Long`: these are unsigned 64-bit values and exceed
  `Long/MAX_VALUE`.
- Context decides the conversion: API input is hex; MBOX input is decimal.
  Never infer a base from an all-digit string.
- RDF export uses `gmail:message/<decimal>` and `gmail:thread/<decimal>` IRIs.

New shared helper namespace (JVM + bb wrappers launched with `-cp src`):

```clojure
(ns astanova.gmail-id)

(defn api-hex->canonical [s])   ; "19f..." -> "186..."
(defn canonical->api-hex [s])   ; "186..." -> "19f..."
(defn canonical? [s])           ; decimal, positive, <= 2^64-1
(defn message-iri [s])
(defn thread-iri [s])
```

### Conflict rules

- Existing `:email/gmail-id` and normalized mapping differ → conflict; report and
  do not overwrite.
- Existing `:email/thread-id` decimal and normalized API thread differ → conflict;
  do not split the thread.
- Existing `:email/thread-id` is a fallback (`Thread-Topic`) and gws supplies a
  Gmail thread → replace with canonical decimal, record source.
- Existing hex IDs from an earlier bad run → migrate to canonical decimal after
  verification; report the migration.
- Dry-run output uses synthetic markers and must never be accepted by
  `update-message-ids`. Current fake `gmail-...` IDs are a hazard.
- Add validation in `update-message-ids` before transacting: canonical format,
  no `gmail-`/`thread-` synthetic values.

## Pipeline position

```
Takeout MBOX
    │
    ▼
Datalevin email entities ───────────────┐
    │                                   │
    ├─ optional gws map IDs             │  email enrichment
    ├─ optional gws fetch bodies        │  (identities, bodies, labels)
    ├─ optional gws refresh labels      │
    └─ optional content fetch           │
    │                                   │
    ▼                                   ▼
candidate compile ──► classify ──► ontology graph ──► local match ──► report
```

Live mode:

```
gws list (query) ──► gws get raw ──► parse MIME ──► upsert by Message-ID
                                                      │
                                                      └─► same pipeline
```

## Enrichment jobs

### G1 — Identity mapping (`map-ids`)

- Input: DB emails, primarily missing `:email/gmail-id`.
- Calls `messages.list` by `rfc822msgid:` (or live source metadata).
- Converts API hex to canonical decimal.
- Output shape:
  ```edn
  {"<Message-ID>" {:gmail-id "186..." :thread-id "186..."}}
  ```
- Updates only after conflict checks; dry-run output is marked synthetic.
- Does not overwrite existing IDs with a different canonical value.

### G2 — Body enrichment (`fetch-bodies`)

- Input: emails with a canonical Gmail ID and empty/blank body.
- Calls `gws gmail +read` (or raw MIME for live mode).
- Stores `:email/body-truncated`, `:email/body-length`, and provenance.
- Never overwrites a non-empty Takeout body unless `--force`.
- Records `:email/body-source` (`:takeout`, `:gws-read`, `:gws-raw`).

### G3 — Label refresh

- Input: emails with canonical Gmail IDs.
- Resolves API `labelIds` through `labels.list` → names.
- MBOX labels are a snapshot; live labels are current.
- Default: union with existing labels (preserve context).
- `--replace-labels`: replace with live set; use when label-based source
  selection must reflect Gmail now.
- Records `:email/labels-source` / `:email/labels-updated-at`.
- Gmail label names feed the label taxonomy graph; they do not become ontology
  entity categories.

### G4 — Live source ingest

- Input: source spec with a Gmail query.
- `gws messages.list` (paginated) → `messages.get` with `format=raw`.
- Base64url-decode → reuse the MIME parser → email map.
- Upsert by `:email/id` (Message-ID), not by Gmail ID.
- Labels: resolve `labelIds` to names.
- Then run G1/G2/G3 as needed.
- This is the only mode that accepts legacy Gmail `:query` strings.

### G5 — Content enrichment

- `fetch-content` is independent of gws: it fetches arXiv/GitHub/YouTube pages
  from `:email/links`.
- Content entities remain evidence linked to emails; ontology extraction v1
  uses email bodies only.
- Future: include `:content/body` as an optional candidate source; no schema
  change needed.

## Data model additions

Email provenance attributes (all optional, additive):

| Attribute | Type | Meaning |
|---|---|---|
| `:email/body-source` | keyword | `:takeout`, `:gws-read`, `:gws-raw` |
| `:email/labels-source` | keyword (many) | `:takeout`, `:gws` |
| `:email/labels-updated-at` | instant | last live label refresh |
| `:email/gmail-id-source` | keyword | `:takeout`, `:gws` |
| `:email/enriched-at` | instant | last enrichment touch |

No `:email/gmail-id` uniqueness constraint yet: existing DBs may contain
hex/decimal duplicates. Add it after a normalization/migration pass, not before.

## Labels and ontology

- `:email/labels` remain strings (names) for query/source selection.
- The label taxonomy graph (`resources/labels-categorized.json`) maps names to
  categories and parents; `label-ancestor` rules provide hierarchy.
- Gmail system labels (`INBOX`, `UNREAD`, `CATEGORY_*`) are resolved to display
  names where possible; unknown IDs are retained as IDs with a warning.
- Label refresh affects source selection, not entity category classification.
- A label rename is not an ontology term merge; it is label metadata.

## Ontology/provenance boundary

- GWS enrichment writes **email facts**, not ontology facts.
- The candidate compiler reads enriched bodies/labels and proposes terms.
- The classifier writes ontology graph facts; the source of a term is the
  classifier/seed/manual, not gws.
- If term mentions are added later, they can link to email entities:
  `:mention/email`, `:mention/term`, `:mention/context`. That is a separate
  feature; the cache remains term → category.

## Failure and idempotency

- No `gws` in unit tests; use recorded JSON/MIME fixtures.
- Auth failure/rate limit/not-found are distinct outcomes; no silent writes.
- Identity mapping is resumable (`--skip`, `--limit`, delays).
- Body and label enrichment are idempotent by canonical ID.
- Live ingest is idempotent by Message-ID.
- Dry-run never mutates the DB.

## Acceptance tests

1. Hex↔decimal round-trip for real 64-bit IDs; overflow-safe.
2. `map-ids` output is canonical decimal.
3. `update-message-ids` refuses synthetic/dry-run IDs.
4. Existing decimal thread does not change when API value normalizes to the same
   decimal.
5. A genuinely conflicting thread/Gmail ID is reported, not overwritten.
6. Existing hex IDs migrate to decimal.
7. Body enrichment sets source and does not overwrite non-empty Takeout bodies.
8. Label refresh union vs replace behaves as specified.
9. Live raw ingest upserts by Message-ID without duplicates.
10. No-network tests cover the adapter with recorded fixtures.

## Open questions

1. Canonical decimal strings vs canonical `gmail:thread/<decimal>` IRIs in the
   DB. Proposal: strings in DB, IRIs in RDF export.
2. Add `:thread` entities now or keep `:email/thread-id` strings? Proposal:
   strings for v1; thread entities only if thread-level ontology facts become
   necessary.
3. Default label refresh: union (proposed) vs replace.
4. Should `--force` be required to replace a non-empty body, or should gws body
   only fill blanks? Proposal: only blanks by default; `--force` explicit.
