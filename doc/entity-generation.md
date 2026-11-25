# Entity Generation: Jobs, TV, and Evidence

Status: **E1, E2a, E2b, E3 implemented; E2c/E4+ design**, companion to
[Legacy Gmail Pipeline Port Intent](legacy-pipeline-port-intent.md),
[Ontology Reasoning](ontology-reasoning.md), and
[GWS Enrichment](gws-enrichment.md).

## What the legacy generators actually produce

`fetch_linkedin_emails.py` does **not** emit job entities. It emits an array of
enriched email records (`linkedin_emails.json`); each LinkedIn email can contain
several job cards:

```
Senior Fullstack Software Engineer, AI Agents
11x
Toronto
View job: https://www.linkedin.com/comm/jobs/view/4377264410/...
---------------------------------------------------------
```

`generate_tv_guide.py` does **not** emit TV entities either. It groups
`fitness.json` email records into four hard-coded channels and renders Markdown.
The useful entity facts are implicit:

- channel: CH1 boxing, CH2 flexibility, CH3 self-defense, CH4 general fitness
- program: a fitness email / broadcast
- episode: a YouTube video attached to that email
- presenter/host: the email sender or YouTube `author_name`

Our port should make those entities explicit in the database, then render the
TV guide and job views from the entity graph.

## Entity model

Entities are ontology **individuals**, distinct from ontology **terms**.

| Entity class | Meaning | Natural key |
|---|---|---|
| `:job-posting` | one job listing | canonical company/title/location hash |
| `:tv-channel` | curated channel | fixed channel id |
| `:tv-program` | one broadcast/email in a channel | source email id |
| `:tv-episode` | one YouTube video | YouTube video id |

Entity classes are separate from term categories
(`companies/roles/fields/job_types/locations`). A job is not a term; it links
to term individuals.

### Common entity attributes

| Attribute | Type | Notes |
|---|---|---|
| `:entity/id` | string unique | deterministic, stable across reruns |
| `:entity/class` | ref | entity class (`:job-posting`, `:tv-program`, ...) |
| `:entity/title` | string | display title |
| `:entity/source` | keyword | `:linkedin`, `:builtin`, `:google-jobs`, `:fitness-email` |
| `:entity/status` | keyword | `:active`, `:stale`, `:merged`, `:provisional` |
| `:entity/created-at` | instant | first seen |
| `:entity/updated-at` | instant | last upsert |
| `:entity/confidence` | double | optional extraction confidence |
| evidence | derived | query `:evidence/entity` in reverse; no stored back-ref |

### Job posting

| Attribute | Type | Notes |
|---|---|---|
| `:job/title` | string | raw title |
| `:job/company-raw` | string | raw company |
| `:job/role-raw` | string | raw role/title for term resolution |
| `:job/location-raw` | string many | raw locations |
| `:job/source-id` | string | LinkedIn job id, etc. |
| `:job/url` | string many | canonical job URLs |
| `:job/posted-at` | instant | if known |
| `:job/job-type` | ref many | term category `job_types` |
| `:job/company` | ref | term category `companies` |
| `:job/role` | ref | term category `roles` |
| `:job/location` | ref many | term category `locations` |
| `:job/field` | ref many | term category `fields` |
| `:job/salary-text` | string | optional |
| `:job/description` | string | optional |
| `:job/dedup-key` | string | normalized company/title/location |

### TV channel

| Attribute | Type | Notes |
|---|---|---|
| `:channel/code` | string | `CH 1` |
| `:channel/name` | string | display name |
| `:channel/tagline` | string | description |
| `:channel/topic` | ref | term category `fields` |
| `:channel/program` | ref many | programs on this channel |

Channels are seeded from `ontology/tv-channels.edn`, not extracted from email.

### TV program

| Attribute | Type | Notes |
|---|---|---|
| `:program/channel` | ref | `:tv-channel` |
| `:program/airtime` | instant | email date |
| `:program/synopsis` | string | snippet |
| `:program/body` | string | truncated body / show notes |
| `:program/source-email-id` | string | Message-ID |
| `:program/episode` | ref many | `:tv-episode` |
| `:program/field` | ref many | term category `fields` |

### TV episode

| Attribute | Type | Notes |
|---|---|---|
| `:episode/video-id` | string unique | YouTube id |
| `:episode/title` | string | oEmbed title |
| `:episode/author` | string | oEmbed author |
| `:episode/author-url` | string | oEmbed author URL |
| `:episode/watch-url` | string | canonical watch URL |
| `:episode/thumbnail-url` | string | oEmbed thumbnail |
| `:episode/available` | boolean | oEmbed success |
| `:episode/program` | ref many | programs that feature it |

An episode is canonical by video id and can appear in multiple programs.
`program/episode` and `episode/program` are inverse relations; store one
direction and derive the other with a Datalog rule.

## Evidence and provenance

Keep extraction provenance out of the entity's identity:

| Attribute | Type | Notes |
|---|---|---|
| `:evidence/id` | string unique | hash(entity, email, url, parser) |
| `:evidence/entity` | ref | entity being supported (the only stored direction) |
| `:evidence/email-id` | string | Message-ID |
| `:evidence/email` | ref | optional if email exists in DB |
| `:evidence/url` | string | source URL |
| `:evidence/parser` | keyword | `:linkedin-email`, `:tv-fitness-email` |
| `:evidence/snippet` | string | raw card/context |
| `:evidence/observed-at` | instant | extraction time |

Jobs can be discovered in multiple emails/sources. Upsert adds evidence records
instead of duplicating the job. Program/episode evidence points at the source
email and video metadata.

## Ontology integration

### Entity classes in `ontology/schema.edn`

Add a separate `:entity-classes` hierarchy:

```edn
{:entity-classes
 {:entity     {:label "Entity"}
  :posting    {:label "Posting" :parents [:entity]}
  :job-posting {:label "Job Posting" :parents [:posting]}
  :media      {:label "Media Entity" :parents [:entity]}
  :tv-channel {:label "TV Channel" :parents [:media]}
  :tv-program {:label "TV Program" :parents [:media]}
  :tv-episode {:label "TV Episode" :parents [:media]}}
 :entity-disjoint
 [[:job-posting :tv-channel]
  [:job-posting :tv-program]
  [:job-posting :tv-episode]
  [:tv-channel :tv-program]
  [:tv-channel :tv-episode]
  [:tv-program :tv-episode]]}
```

### Term links

- Job structured fields map directly to terms:
  - company → `companies`
  - role → `roles`
  - location → `locations`
  - job type → `job_types`
  - fields/technologies → `fields`
- TV fields and channel topics map to `fields`.
- Entity links use term **refs**, not free strings, when a cache/canonical term
  exists. Raw strings are always retained for audit.
- If a structured value is not in the ontology cache, create a
  **provisional term**:
  - `:term/status :provisional`
  - `:term/source :entity-extraction`
  - category inferred from the field role
  - **not exported to `ontology_cache.json`** and not used by local matching
  - promoted by the normal classifier/review flow later

This keeps the approved cache stable while still letting jobs/TV entities join
the graph.

### Reasoning rules

Add entity relations to `ontology/rules.edn`:

```clojure
[(entity-class ?e ?c) [?e :entity/class ?c]]
[(entity-class ?e ?anc) (entity-class ?e ?c) (subclass-entity ?c ?anc)]

[(entity-term ?e ?t) [?e :job/company ?t]]
[(entity-term ?e ?t) [?e :job/role ?t]]
[(entity-term ?e ?t) [?e :job/location ?t]]
[(entity-term ?e ?t) [?e :job/job-type ?t]]
[(entity-term ?e ?t) [?e :job/field ?t]]
[(entity-term ?e ?t) [?e :program/field ?t]]

[(channel-program ?c ?p) [?c :channel/program ?p]]
[(episode-program ?e ?p) [?p :program/episode ?e]]
[(job-field-closure ?j ?f) [?j :job/field ?f]]
[(job-field-closure ?j ?f) (job-field-closure ?j ?base)
                         (broader ?base ?f)]
```

### Validation

Entity upsert must reject or warn on:

- entity class missing or not an entity class
- job company term category not `companies`
- job role term category not `roles`
- job location term category not `locations`
- job job-type term category not `job_types`
- job/program field term category not `fields`
- entity class disjointness violation
- program channel not a `tv-channel`
- episode linked to a non-program
- duplicate `:episode/video-id` or `:entity/id`

Missing optional fields (salary, description, job type) are warnings, not
errors.

## Scripts and storage flow

```
data/exports/*.json or takeout query EDN
        │
        ├─ scripts/generate-jobs.clj ──► data/entities/jobs.edn
        └─ scripts/generate-tv.clj  ──► data/entities/tv.edn
                                              │
                                              ▼
                              ./takeout entities upsert --from ...
                                              │
                                              ▼
                       Datalevin email + ontology + entity + evidence facts
                                              │
                              ┌───────────────┴───────────────┐
                              ▼                               ▼
                    entity Datalog queries           TV-guide / job reports
```

Scripts are thin Babashka wrappers over shared pure Clojure namespaces (so the
parsers are covered by the JVM test suite). They do not open Datalevin and do
not classify terms.

### `scripts/generate-jobs.clj`

- Input: JSON/EDN email array, or stdin from `takeout query`.
- Source auto-detection:
  - `from` contains `jobs-noreply@linkedin.com` → LinkedIn parser
  - `from` contains `support@builtin.com` → Built In parser (later)
  - `from` contains `notify-noreply@google.com` → Google jobs parser (later)
- LinkedIn parser:
  - split on long `---` separators
  - title/company/location lines
  - `View job:` URL and job id
  - skip `Apply with resume & profile` and tracking noise
- Output entity maps with raw fields and evidence, not DB transactions.

### `scripts/generate-tv.clj`

- Input: fitness email array (JSON/EDN) or stdin.
- Channel mapping uses `ontology/tv-channels.edn` rules:
  - labels first (`self_defense`, `flexibility`, `boxing`)
  - subject keywords next
  - fallback `CH4_GENERAL_FITNESS`
- Program per email; episodes from `youtube_videos` (legacy JSON) or
  `youtube_video_ids` + oEmbed cache.
- Output channels, programs, episodes, and evidence.

### `entities upsert`

New `takeout entities upsert` command:

- `--from data/entities/jobs.edn`
- `--from data/entities/tv.edn`
- `--dry-run`
- resolves raw fields to term refs
- creates provisional terms for misses
- upserts entities by `:entity/id`
- merges/adds evidence, URLs, source ids
- validates ontology categories and disjointness
- closes the connection in `finally`

## Where the upsert belongs

There are two different inputs and two different upsert layers.

### 1. Legacy email exports = enriched email records

`linkedin_emails.json`, `fitness.json`, `datalog-agents.json`, etc. are arrays
of **email records**, not job/TV entity arrays. Saving them means importing or
upserting emails by `:email/id` (Message-ID), exactly like MBOX ingestion.

Placement:

- `src/astanova/import/emails.clj` — adapt legacy email-export keys to the
  internal email map; canonicalize Gmail/thread IDs; parse dates; normalize
  body/labels/links.
- `src/astanova/ingest.clj` — expose a generic `upsert-emails!` from the
  existing transform/batch path. `email-map->entity` already uses
  `:email/id` unique identity, so `d/transact!` is an upsert.
- CLI: `takeout import-emails --from ...` (or `takeout upsert-emails`) with
  `--dry-run` and optional `--force` for body replacement.

Do not put this in `db.clj`; that namespace stays schema/connection only.

### 2. Generated entity EDN = entities/evidence

Our Babashka generators produce job/TV entity maps. These are stored by a
separate store namespace:

- `src/astanova/entity/store.clj` — `upsert-entities!`:
  - seed/ensure entity classes
  - resolve term refs or create provisional terms
  - upsert by `:entity/id`
  - upsert evidence by `:evidence/id`
  - merge URLs/locations/evidence on rerun
  - validate ontology constraints
  - batch transactions
- CLI: `takeout upsert-entities --from data/entities/jobs.edn` with
  `--dry-run` and a final connection close.

`src/astanova/entity.clj` remains pure (IDs/validation); `astanova.entity.store`
owns Datalevin access. This mirrors the existing split between
`ingest.clj` (email transformation/transactions) and `db.clj` (schema/conn).

### Existing upsert pattern to reuse

`ingest/email-map->entity` builds a transaction map with a tempid plus the
unique `:email/id`; Datalevin upserts on that identity. `upsert-content` uses
the same pattern with `:content/id`.

Entity storage follows it with:

- `:entity/id` unique identity
- `:evidence/id` unique identity
- explicit refs for classes, terms, email evidence, programs/episodes

Do not create a second generic “write any map” function; keep domain-specific
transformers (email, content, entity) with shared batching.

## Importing legacy email exports

The adapter should:

- accept JSON arrays and `takeout query` maps with `:results`
- use `messageId`/`email/id` as the unique key; skip missing IDs
- canonicalize Gmail `gmailId`/`threadId` from API hex to decimal
- map `internalDate`/`internalDateIso` to `:email/date`
- map `from`, `to`, `subject`, `labels`, `snippet`
- map `body_truncated` to `:email/body-truncated`; if it is HTML, store it as
  `:email/html` and derive plain text where possible
- map `body_extract.links` to `:email/links`
- set `:email/source "legacy-json"` (or the source file name)
- never overwrite a richer existing Takeout body unless `--force`
- be idempotent on re-import

`body_extract` itself is derived, not canonical. It should not be stored as
email fields. If we later want term mentions/evidence, that is an entity/mention
layer concern.

## Ontology store decision

Use **one Datalevin database** for email facts, ontology terms/classes, entities,
and evidence. Attributes are namespaced (`:email/*`, `:term/*`, `:class/*`,
`:entity/*`, `:job/*`, `:tv/*`, `:evidence/*`), and refs can join across them.

This avoids a second database and lets `:job/source-email` / `:program/source-email`
join directly to `:email/id`.

Evidence stores only `:evidence/entity`; entity evidence is queried in reverse.
Storing both directions creates a Datalevin transaction cycle, so the
`:entity/evidence` attribute is intentionally not used. Existing databases gain additive schema attributes;
no destructive migration is required.

## Dedup and idempotency

- Job `:entity/id` = `job:` + hash(normalized company | title | location |
  posting month).
- If a source job id exists, store it in `:job/source-id` and prefer the
  canonical URL `https://www.linkedin.com/jobs/view/<id>/`.
- Re-running the script/upsert must not duplicate entities or evidence.
- Evidence id = hash(entity-id | email-id | url | parser).
- Job URLs and source emails accumulate; title/company/location update only if
  the new value is non-blank and the entity is not manually pinned.
- TV program id = `program:` + email Message-ID; episode id = `episode:` +
  video id; channel ids are fixed.

## Reasoning queries unlocked

- jobs at a company, including company aliases
- jobs by role/location/job type
- jobs matching a field or any broader field
- jobs discovered by source, date, or evidence email
- TV programs by channel, field, or date
- episodes and their creators/programs
- programs featuring a term/video
- entity counts/trends by month and source

## Implemented commands

```bash
# Import a legacy email export
./takeout import-emails --from linkedin_emails.json

# Generate and store job entities
bb scripts/generate-jobs.clj --from linkedin_emails.json \
  --out data/entities/jobs.edn
./takeout upsert-entities --from data/entities/jobs.edn
```

`import-emails` preserves existing non-empty bodies unless `--force`; labels
and links are unioned. `upsert-entities` seeds classes, stores entities and
evidence, is idempotent on rerun, and supports `--dry-run`.

## Phases

### E1 — Entity model and schema — **done**
- Entity classes added to `ontology/schema.edn`; entity/evidence attributes
  merged into the main Datalevin schema.
- `astanova.entity` provides deterministic IDs, canonical LinkedIn URLs,
  dedup keys, and category-aware validation.
- JVM tests cover IDs, class constraints, and schema round-trips.

### E2a — Legacy email export import — **done**
- `src/astanova/import/emails.clj` adapter + `takeout import-emails`.
- Canonical Gmail IDs, HTML/text separation, labels/links, idempotent by
  Message-ID; preserves existing bodies unless `--force`.

### E2b — `upsert-entities` — **done**
- `src/astanova/entity/store.clj`; seeds classes; upserts entities/evidence;
  merges URLs/locations; validates; supports `--dry-run` and closes the
  connection in `finally`.
- Evidence is stored only on `:evidence/entity`; reverse queries avoid a
  Datalevin transaction cycle.

### E2c — Term resolution/provisional terms
- Resolve structured fields against the cache; create provisional terms for
  misses; exclude them from the approved cache.

### E3 — LinkedIn job parser + script — **done**
- `astanova.entity.linkedin` parses cards, canonicalizes URLs, and deduplicates
  by entity id.
- `scripts/generate-jobs.clj` reads JSON/EDN and writes entity EDN.
- Golden fixture/test covers 2 emails, 4 cards, 3 jobs, and duplicate evidence.
- Import `linkedin_emails.json` remains a smoke test for E2.

### E4 — TV entities + script
- Channel EDN, mapping rules, program/episode generation.
- YouTube metadata from JSON/cache; later oEmbed.
- Import `fitness.json`; verify channels/programs/episodes.

### E5 — Entity queries and TV-guide rendering
- Query helpers and `generate-tv-guide` from DB.
- Preserve masking and channel format.

### E6 — Other job sources + provisional-term promotion
- Built In / Google jobs parsers.
- Promote/review provisional terms.

## Open decisions

1. Same database for emails + ontology + entities? Proposal: yes.
2. Job dedup key includes posting month? Proposal: yes, to avoid merging
   reposted roles across years.
3. Episode entities canonical by video id and shared across programs? Proposal:
   yes, with `:program/episode` many.
4. Create provisional terms for unmatched structured fields? Proposal: yes,
   status `:provisional`, excluded from the cache.
5. Do we need a separate `:video-creator` entity? Proposal: no for v1; store
   author strings and optionally link later.
6. Should TV channels be seeded from EDN or inferred from labels? Proposal:
   seeded EDN; mapping rules infer only which channel a program belongs to.
