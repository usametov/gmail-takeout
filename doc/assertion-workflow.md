# Assertion Workflow

Status: **design for review**. Companion to
[Task Extraction Design](gws-task-extraction-design.md) and
[Task Extraction Intent](gws-task-extraction-intent.md).

This document details how an extracted field becomes a reviewable assertion,
how humans approve/correct it, and how re-extraction behaves.

## 1. Core rule

An extracted field value is not directly the entity's truth. It becomes a
**candidate assertion** with provenance. The effective value is resolved from:

1. an active human correction
2. an approved assertion
3. the raw email/entity field as a fallback

Candidate/rejected/conflicted assertions are visible in review mode but are not
silently treated as approved facts.

## 2. Assertion lifecycle

```text
extract
  │
  ▼
:candidate ──approve──► :approved
  │                        │
  ├──reject───────────────►:rejected
  │
  ├──correct──────────────►:corrected
  │                           │
  │                     correction withdrawn
  │                           ▼
  │                      :approved / :candidate
  │
  ├──newer assertion──────►:superseded
  │
  └──disagrees with a
     correction/approval──►:conflicted
```

| Status | Meaning |
|---|---|
| `:candidate` | extracted, not reviewed |
| `:approved` | human accepted |
| `:rejected` | human rejected; excluded from effective values |
| `:corrected` | an active correction replaces the value |
| `:superseded` | a newer assertion from the same extractor/field replaced it |
| `:conflicted` | a new extraction disagrees with a correction or approved value |
| `:stale` | source/email/entity no longer available (diagnostic only) |

Status transitions are append-only events; the assertion itself is not deleted.

## 3. Assertion record

```edn
{:assertion/id "assertion:<sha256>"
 :assertion/revision 1
 :assertion/subject-type :email
 :assertion/subject-id "<...@arxiv.org>"
 :assertion/field :paper/title
 :assertion/raw-value "Original title"
 :assertion/value "Normalized title"
 :assertion/value-type :string
 :assertion/multi false

 :assertion/status :candidate
 :assertion/confidence 0.9

 :assertion/task-id "arxiv-agentic-kg"
 :assertion/task-version 1
 :assertion/run-id "task-run:..."
 :assertion/extractor-id "arxiv-paper-v1"
 :assertion/extractor-version 1
 :assertion/method :html-meta

 :assertion/locator {:mime "text/html"
                     :path "meta[name=citation_title]"}
 :assertion/snippet "Original title"
 :assertion/query "from:no-reply@arxiv.org"

 :assertion/observed-at #inst "2026-11-25T00:00:00Z"
 :assertion/created-at #inst "2026-11-25T00:00:00Z"
 :assertion/updated-at #inst "2026-11-25T00:00:00Z"

 :assertion/llm nil
 :assertion/extra nil}
```

### Identity

```text
sha256(subject-type | subject-id | field | extractor-id | extractor-version |
       locator | raw-value)
```

Consequences:

- same extraction re-run → same assertion id
- changed raw value or locator → new assertion
- multi-valued field → one assertion per value
- human correction does not change the assertion id; it references it

`revision` increments on status/provenance updates; `updated-at` changes with it.

### Revision history

The current assertion is a projection; the history is append-only and
automation-friendly:

```edn
{:assertion-revision/id "assertion-revision:<sha256>"
 :assertion-revision/assertion-id "assertion:..."
 :assertion-revision/number 2
 :assertion-revision/previous-id "assertion-revision:..."
 :assertion-revision/action :approved
 :assertion-revision/from-status :candidate
 :assertion-revision/to-status :approved
 :assertion-revision/value "Normalized title"
 :assertion-revision/actor :human
 :assertion-revision/reviewer "human"
 :assertion-revision/reason "paper title checked"
 :assertion-revision/at #inst "..."
 :assertion-revision/run-id "task-run:..."
 :assertion-revision/llm nil}
```

Rules:

- every import/review/correction/retirement appends a revision
- revisions are immutable
- the assertion's `:assertion/revision` points at the latest
- automation can consume the revision stream without mutating assertions
- revision IDs are deterministic for idempotent actions; a reviewed status
  transition is not applied twice

### Extensible provenance

Core fields are fixed. `:assertion/extra` is an optional EDN map for
extractor-specific details:

```edn
{:assertion/extra {:html-selector "meta[name=citation_title]"
                   :regex-groups ["..."]
                   :source-header "X-..."}}
```

This leaves room for field-level provenance without schema churn.

### LLM provenance

When `:llm/enabled` is explicit:

```edn
{:assertion/method :llm
 :assertion/llm {:provider "deepseek"
                 :model "..."
                 :prompt-id "..."
                 :temperature 0.0
                 :request-id "..."
                 :raw-response-sha256 "..."}}
```

LLM assertions are candidates by default and must pass normal review.

### Local-model automation

A local model can eventually replace some heuristic extraction, but its outputs
stay assertions. Automation policy is explicit per task/extractor/field:

| Level | Behavior |
|---|---|
| L0 | human reviews every assertion |
| L1 | human reviews low-confidence/conflicted subset |
| L2 | auto-approve calibrated high-confidence values + sampled review |
| L3 | fully autonomous (not planned) |

Requirements for L1/L2:

- gold-set evaluation for the extractor/model/field
- calibrated confidence thresholds
- every auto-approval is a revision with `:actor :system` and policy id
- auto-approval never overrides a human correction
- sampled human review and rollback/retirement
- sensitive fields excluded unless explicitly allowed

Auto-approved assertions remain revocable; the revision history records the
policy and model version.

### Embedding-assisted review

Embeddings can cluster similar assertions, retrieve evidence, prioritize
low-confidence items, and propose corrections. They do not approve assertions.
Semantic similarity is not proof of correctness.

## 4. Import workflow

### Step 1: import emails first

```bash
./takeout import-emails \
  --from data/tasks/arxiv-agentic-kg/emails.json \
  --source task-arxiv-agentic-kg
```

Assertions about emails validate that `:assertion/subject-id` exists as
`:email/id`; assertions about entities validate the `:entity/id`. **The subject
must exist before assertion import.** A missing subject fails the import with a
clear error. Import emails (and any staged entities) first.

### Step 2: import assertions

```bash
./takeout assertions import \
  --from data/tasks/arxiv-agentic-kg/assertions.edn \
  --task arxiv-agentic-kg \
  --run-id task-run:...
```

Import rules:

- all new assertions enter as `:candidate`
- existing identical assertion ids are updated idempotently
- import never approves anything
- import never overwrites an active correction
- a new assertion for the same subject/field/extractor:
  - supersedes the old uncorrected assertion
  - if the old assertion is corrected, the new one is `:conflicted`
- import returns counts: new, unchanged, changed, superseded, conflicted

### Step 3: review

```bash
./takeout assertions review \
  --task arxiv-agentic-kg \
  --status candidate \
  --format edn \
  --out data/tasks/arxiv-agentic-kg/review.edn
```

The review file is human-edited:

```edn
{:review/version 1
 :review/task "arxiv-agentic-kg"
 :review/run-id "task-run:..."
 :review/generated-at #inst "..."
 :items
 [{:assertion/id "assertion:..."
   :action :approve}
  {:assertion/id "assertion:..."
   :action :correct
   :new-value "The correct title"
   :reason "extractor used the email subject"}
  {:assertion/id "assertion:..."
   :action :reject
   :reason "not about this paper"}]}
```

Actions:

| Action | Result |
|---|---|
| `:approve` | assertion `:approved` |
| `:reject` | assertion `:rejected` |
| `:correct` | creates an active correction; assertion `:corrected` |
| `:noop` | remains candidate |

Apply review:

```bash
./takeout corrections import \
  --from data/tasks/arxiv-agentic-kg/review.edn
```

The review application is transactional. If an assertion changed since the
review file was generated, the item is rejected as stale and must be reviewed
again.

## 5. Correction model

A correction is a separate entity, not a mutation of the assertion.

```edn
{:correction/id "correction:<sha256>"
 :correction/assertion-id "assertion:..."
 :correction/subject-type :email
 :correction/subject-id "<...@arxiv.org>"
 :correction/field :paper/title
 :correction/op :replace
 :correction/old-value "..."
 :correction/new-value "The correct title"
 :correction/reason "extractor used the email subject"
 :correction/reviewer "human"
 :correction/status :active
 :correction/scope :single
 :correction/batch-id nil
 :correction/extractor-id "arxiv-paper-v1"
 :correction/extractor-version 1
 :correction/created-at #inst "..."
 :correction/updated-at #inst "..."}
```

Operations:

- `:replace` — replace one assertion's effective value
- `:remove` — remove one value from a multi-valued field
- `:add` — add a value with no original assertion; `:assertion-id` may be nil

Corrections are immutable in meaning. Editing a correction creates a new
correction and marks the old one `:superseded` or `:withdrawn`; the audit trail
remains.

### Effective values

For a subject/field:

```text
approved assertions
  - rejected
  - superseded
  + active replacements/additions
  - active removals
```

Single-valued field validation:

- more than one effective value is a conflict
- conflict must be resolved by correction/rejection before use

Multi-valued fields:

- deduplicate effective values
- preserve contributor assertion ids for provenance
- removals target a specific assertion/value

## 6. Approving a new value and retiring a correction

When a corrected assertion is superseded by a better extraction, a human can
approve the new value and retire the old correction in one review action.

Review item:

```edn
{:assertion/id "assertion:new"
 :action :approve-new
 :retire-correction-id "correction:old"
 :reason "new extractor parsed the employer correctly"}
```

Transaction:

1. new assertion → `:approved`
2. old corrected assertion → `:superseded`
3. old correction → `:retired`
4. both assertions get revision entries
5. correction gets a retirement event/revision

Requirements:

- the new assertion and the correction must refer to the same subject/field
- the review file must include the expected current assertion/correction
  revisions; stale review files fail
- a correction is never deleted; `:retired` records who/why/when and what
  superseded it
- the effective value becomes the newly approved assertion

The inverse action, `:keep-correction`, rejects the new assertion and leaves the
correction active.

## 7. Bulk corrections with per-record conflicts

A bulk correction cannot always set one literal value. The Google Jobs example
is per-record: the bad value is `Google`, but each record has a different
employer.

The workflow is:

1. collect affected assertions by extractor/version/field
2. assemble an evidence bundle per assertion:
   - raw value and current correction
   - source email id and body snippet/locator
   - sender/headers
   - extractor id/version
   - task/query
3. optionally send each bundle to an explicit LLM prompt
4. log the prompt, model, response, request id, and evidence hash
5. store the result as a **correction proposal**, not an active correction
6. run agentic reasoning/HITL review over the proposals
7. only accepted proposals become corrections

Proposal record:

```edn
{:correction-proposal/id "correction-proposal:..."
 :correction-proposal/batch-id "..."
 :correction-proposal/assertion-id "..."
 :correction-proposal/field :job/company
 :correction-proposal/current-value "Google"
 :correction-proposal/proposed-value "Apex Systems"
 :correction-proposal/evidence {:email-id "..."
                                :snippet "..."
                                :locator {...}}
 :correction-proposal/method :llm
 :correction-proposal/llm {:provider "..." :model "..." :prompt-id "..."
                           :request-id "..." :response-sha256 "..."}
 :correction-proposal/status :proposed
 :correction-proposal/created-at #inst "..."}
```

Proposals are never effective values. Accepting a proposal creates a normal
correction with reviewer/reason and revision history. Rejecting it remains
auditable.

## 8. Re-extraction workflow

When the task is re-run, import a new run:

```bash
bb scripts/tasks/arxiv-agentic-kg/run.clj --from raw.json
./takeout assertions import --from .../assertions.edn
```

Diff report:

```text
new:        3
unchanged: 96
changed:    4
superseded: 2
conflicted: 1
```

Rules:

- identical assertion ids → unchanged; update `observed-at`/run metadata
- new value/locator → new candidate
- old uncorrected assertion for same subject/field/extractor → superseded
- old corrected assertion → preserved; new assertion is `:conflicted`
- old approved but differing assertion → new candidate; human must choose
- assertions missing from the new run are **not** automatically stale unless
  the run covers the same source window explicitly

`--full-window` (design option) may mark missing assertions stale; default is
conservative because a limited `--limit`/window is not evidence of absence.

### Diff review

`assertions review --diff` shows:

- new/changed/conflicted assertions
- corrections that remain active
- exact old/new values and extractor versions
- suggested action (`approve`, `keep correction`, `reject new`)

## 9. Coverage proof and stale assertions

Missing assertions from a later run are **not** proof that the underlying email
or fact disappeared. A task may use a smaller window, `--limit`, a retry, or a
different query.

A run should record coverage:

```edn
{:run/id "task-run:..."
 :run/task-id "arxiv-agentic-kg"
 :run/task-version 1
 :run/query "from:no-reply@arxiv.org after:2026-01-01"
 :run/account "me"
 :run/window {:since "2026-01-01" :until "2026-11-25"}
 :run/pagination {:pages 5 :last-token nil :exhausted? true}
 :run/limits {:max-results nil :limit nil}
 :run/messages {:listed 465 :fetched 465 :ids-sha256 "..."}
 :run/errors []
 :run/status :complete
 :run/as-of #inst "..."}
```

A new run can only be considered a coverage superset when:

- same account
- same query (or a formally narrower, equivalent query)
- window contains the old window
- pagination exhausted
- no `--limit`/max-results truncation
- listing and fetching counts agree
- no fetch errors
- status `:complete`

Even then, deletion/change in Gmail is not proof of assertion falsity. Proposed
policy:

- default: never auto-mark stale
- `--full-window` + complete coverage marks missing assertions `:stale-candidate`
- a human/agent review confirms `:stale`
- assertions tied to an email/entity missing from the DB are `:stale` with a
  diagnostic reason

Coverage proof remains an open design area because “same query” and Gmail
mutation semantics are hard to prove in general.

## 10. Human commands

Proposed CLI:

```bash
# queue
./takeout assertions review --task arxiv-agentic-kg --status candidate

# approve/reject/correct through an EDN file
./takeout corrections import --from review.edn

# single correction
./takeout corrections add \
  --assertion assertion:... \
  --value "The correct title" \
  --reason "extractor used subject"

# list/query
./takeout corrections list --extractor arxiv-paper-v1 --field :paper/title
./takeout assertions stats --task arxiv-agentic-kg

# export/backup
./takeout corrections export --out corrections.edn
```

A human should never need to write raw Datalevin transactions.

## 11. Correction examples

### Google Jobs: company = Google

Problem:

- sender is `notify-noreply@google.com`
- extractor sets `:job/company` = `Google`
- actual employer is elsewhere in the body

Workflow:

1. `assertions review` shows company candidates from `google-jobs-v1`.
2. Correct one assertion to the actual employer.
3. Query all company assertions from `google-jobs-v1` to assess the blast
   radius.
4. Fix the extractor; bump `extractor-version`.
5. Re-run; new company assertions are candidates/conflicted against corrections.
6. Approve corrected values; add a regression fixture.

Bulk action options:

- bulk `:reject` all bad extractor values
- bulk replace only when the replacement is genuinely identical
- otherwise rerun a fixed extractor and review the diff

### arXiv: wrong title

Problem:

- extractor used the email subject instead of the paper title.

Workflow:

1. Correct the title assertion with reason.
2. Approval status becomes `:corrected`.
3. A newer extractor version produces a candidate title.
4. Because a correction is active, the new candidate is `:conflicted`.
5. Review the new value; keep the correction or supersede it with the new
   approved value.

## 12. Quality metrics

Per extractor/version/field:

- candidate count
- approval rate
- rejection rate
- correction rate
- conflict count
- average time from candidate to review

Queries:

- “all corrected company assertions from extractor X”
- “all candidate assertions with confidence below 0.5”
- “which extractor versions have the highest correction rate?”
- “which fields are never reviewed?”

These metrics drive extractor fixes and regression fixtures.

## 13. Audit and safety

- Assertion status transitions are recorded.
- Corrections/reviews record reviewer, reason, and time.
- Assertions and corrections are not silently deleted.
- Agent loops are read-only for assertions/corrections.
- Agents may propose review items, but human approval is required.
- Re-extraction cannot overwrite an active correction.
- Imports/applications are transactional; failed review applications do not
  partially apply.

## 14. Open design details

1. Exact coverage-proof comparison for Gmail query/window equivalence and
   mutation/deletion semantics.
2. Entity promotion: promote a task-scoped entity and its approved assertions
   atomically, or keep them separately reviewable?
3. LLM proposal review UX/thresholds for bulk corrections.
4. Retention/export policy for assertion revisions and correction proposals.
5. How automatic agents may propose but never approve assertions/corrections.
6. Whether task-scoped assertion fields are promoted into shared entity fields
   at all, or remain task evidence forever.
