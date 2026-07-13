# Label Management

Propagate labels across email threads to fill in missing labels and improve
searchability.

## Table of Contents

1. [Overview](#overview)
2. [Thread-level Propagation](#thread-level-propagation)
3. [CLI Usage](#cli-usage)
4. [Programmatic API](#programmatic-api)
5. [How It Works](#how-it-works)
6. [Use Cases](#use-cases)

---

## Overview

Gmail labels are a primary retrieval axis, but they are **sparse** — most emails
carry only a few labels, and many carry none. For example, a thread about
"trading" might have only one email with the `trading` label (the one you
manually tagged), while the rest of the conversation is unlabeled.

Thread-level label propagation fills in these gaps: if any email in a thread
has a label, **all** emails in that thread receive it.

---

## Thread-level Propagation

### Strategy

1. Find all threads that contain at least one email with the target label.
2. Collect all emails in those threads.
3. For each email that doesn't already have the label, add it.

### What it does NOT do

- It does **not** strip existing labels.
- It does **not** propagate to unrelated threads.
- It does **not** touch emails without a `thread-id` (orphaned emails).

---

## CLI Usage

### Preview (dry-run)

Always preview before applying:

```bash
./takeout -d emails.db propagate -l "trading" --dry-run
```

Output:
```
Dry-run for label "trading":
  247 threads affected
  1835 total emails in those threads
  412 emails would receive the label

  Sample of emails to update:
    "Re: Stock alert" — labels: (Sent Inbox)
    "Portfolio update" — labels: (Inbox Important)
    ...
```

### Apply

```bash
./takeout -d emails.db propagate -l "trading"
```

Output:
```
Propagated label "trading" to threads:
  247 threads affected
  1835 total emails in those threads
  412 emails updated
```

### Verify after propagation

```bash
# Check label frequency
./takeout -d emails.db frequencies -s trading

# Query emails with the label
./takeout -d emails.db query -l "trading" -n 5
```

---

## Programmatic API

The `astanova.label` namespace provides the underlying functions.

### propagate-label-to-threads

```clojure
(require '[astanova.db :as db])
(require '[astanova.label :as label])

(let [conn (db/create-conn "emails.db")]
  (try
    (println (label/propagate-label-to-threads conn "trading"))
    ;; => {:threads 247, :total-emails 1835, :updated 412}
    (finally
      (db/close-conn conn))))
```

### preview-propagation

```clojure
(label/preview-propagation (d/db conn) "trading")
;; => {:threads 247
;;     :total-emails 1835
;;     :updated 412
;;     :previews [{:db/id 123
;;                 :subject "Re: Stock alert"
;;                 :existing-labels ("Sent" "Inbox")}
;;                ...]}
```

### Batch propagate multiple labels

```clojure
(doseq [label ["trading" "clojure" "ai/chatbots/watson"]]
  (println "Propagating" label "...")
  (println (label/propagate-label-to-threads conn label)))
```

---

## How It Works

### Data model

```
Email entity:
  :email/thread-id  "abc123"       ;; groups emails into conversations
  :email/labels     ["trading"     ;; multi-valued, can be sparse
                     "Sent"
                     "Inbox"]
```

### Query flow

```
1. Find thread-ids with the label
   ────────────────────────────────
   [:find [?thread ...]
    :where [?e :email/labels "trading"]
           [?e :email/thread-id ?thread]]

2. Get all emails in those threads
   ────────────────────────────────
   [:find [(pull ?e [:db/id :email/labels]) ...]
    :in $ [?thread ...]
    :where [?e :email/thread-id ?thread]]

3. Filter to emails missing the label
   ────────────────────────────────
   (remove #(some #{"trading"} (:email/labels %)) emails)

4. Batch transact
   ────────────────────────────────
   [{:db/id 123, :email/labels ["Sent" "Inbox" "trading"]}
    {:db/id 456, :email/labels ["Inbox" "Important" "trading"]}]
```

### Performance

| Step | Cost |
|------|------|
| Find threads with label | O(labeled-emails) |
| Get emails in those threads | O(thread-emails) |
| Filter | O(emails) |
| Transact | O(updates) |

Total: linear in the number of labeled emails and their thread members.
No quadratic or cross-product behavior.

---

## Use Cases

### Fix incomplete label coverage

After importing a Google Takeout archive, many emails lack labels that their
thread siblings carry. Propagation fills in the gaps:

```bash
# Propagate all important labels
./takeout -d emails.db propagate -l "Important"
./takeout -d emails.db propagate -l "Starred"
./takeout -d emails.db propagate -l "trading"
```

### Prepare for full-text search

Labels are indexed by Datalevin's FTS (if configured). After propagation,
searching by label finds the full conversation, not just the one email that
happened to carry the label originally.

### Audit label coverage

```bash
# Before propagation
./takeout -d emails.db frequencies -s trading
# => 47 emails with label "trading"

# After propagation
./takeout -d emails.db propagate -l "trading"
./takeout -d emails.db frequencies -s trading
# => 459 emails with label "trading"
```

---

## Future Extensions

The current implementation handles **thread-level propagation** — the simplest
and highest-precision strategy. Additional strategies could be added:

- **Sender-based**: learn per-sender label profiles and apply to new emails
- **Similarity-based**: cluster by content similarity, propagate labels within
  clusters

These are more complex and require embeddings or external ML services.
Thread-level propagation is a zero-configuration first step that covers most
real-world use cases.