# Datalevin Query Guide for Email Database

Complete guide to querying your email database after ingestion with `astanova.takeout`.

## Table of Contents

1. [Getting Started](#getting-started)
2. [Basic Queries](#basic-queries)
3. [Filtering & Searching](#filtering--searching)
4. [Working with Threads](#working-with-threads)
5. [Aggregation & Analytics](#aggregation--analytics)
6. [Advanced Patterns](#advanced-patterns)
7. [Helper Functions](#helper-functions)
8. [Performance Tips](#performance-tips)
9. [Real-World Examples](#real-world-examples)

---

## Getting Started

### Connecting to the Database

```clojure
(require '[datalevin.core :as d])
(require '[astanova.db :as db])

;; Open connection
(def conn (db/create-conn "emails.db"))

;; Get immutable database snapshot (recommended for queries)
(def db (d/db conn))

;; Or use the connection directly (for live queries)
;; (d/q '[:find ...] (d/db conn))
```

### Closing the Connection

```clojure
;; Always close when done
(db/close-conn conn)
```

---

## Basic Queries

### Count Total Emails

```clojure
;; Simple count
(d/q '[:find (count ?e) .
       :where [?e :email/subject]]
     db)

;; Using helper function
(db/count-emails db)
```

### Fetch All Emails

```clojure
;; Pull all attributes (caution: can be large!)
(d/q '[:find [(pull ?e [*]) ...]
       :where [?e :email/subject]]
     db)

;; Pull specific fields (recommended)
(d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
       :where [?e :email/subject]]
     db)
```

### Get Single Email by Message-ID

```clojure
;; Using entity API (fast, uses unique identity)
(def email (db/get-email-by-id db "<message-id@example.com>"))

;; Access attributes
(:email/subject email)
(:email/from email)
(:email/body email)
(:email/date email)
```

### List All Senders

```clojure
;; Get unique sender addresses
(d/q '[:find [?from ...]
       :where [?e :email/from ?from]]
     db)

;; Using helper
(db/get-all-senders db)
```

---

## Filtering & Searching

### By Sender

```clojure
;; Exact match
(d/q '[:find [(pull ?e [:email/subject :email/date]) ...]
       :in $ ?sender
       :where [?e :email/from ?sender]]
     db "alice@example.com")

;; Using helper
(db/query-by-sender db "alice@example.com")

;; Pattern matching (case-insensitive)
(d/q '[:find [(pull ?e [:email/subject :email/from]) ...]
       :in $ ?pattern
       :where [?e :email/from ?from]
              [(clojure.string/includes? (clojure.string/lower-case ?from) ?pattern)]]
     db "alice")
```

### By Recipient

```clojure
;; Find emails sent TO you
(d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
       :in $ ?addr
       :where [?e :email/to ?addr]]
     db "me@example.com")

;; Using helper
(db/query-by-recipient db "me@example.com")
```

### By Subject

```clojure
;; Subject contains keyword (case-insensitive)
(d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
       :in $ ?pattern
       :where [?e :email/subject ?s]
              [(clojure.string/includes? (clojure.string/lower-case ?s) ?pattern)]]
     db "meeting")

;; Using helper
(db/query-by-subject db "meeting")
```

### By Date Range

```clojure
;; Emails from 2024
(d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
       :in $ ?start ?end
       :where [?e :email/date ?d]
              [(>= ?d ?start)]
              [(<= ?d ?end)]]
     db
     #inst "2024-01-01T00:00:00.000-00:00"
     #inst "2024-12-31T23:59:59.999-00:00")

;; Using helper
(db/query-by-date-range db
  #inst "2024-01-01"
  #inst "2024-12-31")
```

### By Gmail Labels

```clojure
;; Find emails with "Important" label
(d/q '[:find [(pull ?e [:email/subject :email/from :email/labels]) ...]
       :in $ ?label
       :where [?e :email/labels ?label]]
     db "Important")

;; Using helper
(db/query-by-label db "Important")

;; List all labels
(d/q '[:find [?label ...]
       :where [?e :email/labels ?label]]
     db)

;; Using helper
(db/get-all-labels db)
```

### Full-Text Search

```clojure
;; Search in subject and body
(d/q '[:find [(pull ?e [:email/subject :email/from]) ...]
       :in $ ?term
       :where (or [?e :email/subject ?text]
                  [?e :email/body ?text])
              [(clojure.string/includes? (clojure.string/lower-case ?text) ?term)]]
     db "project update")

;; Using helper
(db/search-emails db "project update")
```

---

## Working with Threads

Threads group related emails together using the `:email/thread-id` attribute, 
which is extracted from the `Thread-Topic`, `References`, or `X-GM-THRID` 
headers (Google Takeout uses `X-GM-THRID`).

### Understanding Thread IDs

```clojure
;; Check if emails have thread IDs
(d/q '[:find (count ?e) .
       :where [?e :email/thread-id ?t]]
     db)

;; Get a sample thread ID
(d/q '[:find ?t .
       :where [?e :email/thread-id ?t]]
     db)
```

### Get All Emails in a Thread

```clojure
;; Using helper function (returns emails ordered by date)
(db/get-thread-emails db "thread-id-here")

;; Manual query (customize pull pattern)
;; Note: Datalevin 0.9.x doesn't support :order-by in queries.
;; Sort results in Clojure instead:
(->> (d/q '[:find [(pull ?e [:email/subject :email/from :email/date :email/body]) ...]
            :in $ ?thread
            :where [?e :email/thread-id ?thread]
                   [?e :email/date ?d]]
          db "thread-id-here")
     (sort #(compare (:email/date %1) (:email/date %2))))
```

### Get Thread Summary

```clojure
;; Get summary with participant count, date range, etc.
(db/get-thread-summary db "thread-id-here")

;; Returns:
;; {:thread-id "..."
;;  :subject "Re: Meeting notes"
;;  :email-count 5
;;  :participants #{"alice@example.com" "bob@test.com"}
;;  :first-date #inst "2024-01-01..."
;;  :last-date #inst "2024-01-05..."}
```

### Get Thread Participants

```clojure
;; Get all unique participants (from and to addresses)
(db/get-thread-participants db "thread-id-here")

;; Returns set of email addresses:
;; #{"alice@example.com" "bob@test.com" "carol@company.com"}
```

### Find Threads by Participant

```clojure
;; Find all threads involving a specific email address
(db/get-threads-by-participant db "alice@example.com")

;; Returns seq of thread IDs:
;; ("thread-1" "thread-2" "thread-3")

;; Combine with thread summary:
(->> (db/get-threads-by-participant db "alice@example.com")
     (map #(db/get-thread-summary db %))
     (sort-by :last-date >))
```

### Get Recent Threads

```clojure
;; Get 10 most recent threads (based on last email date)
(db/get-recent-threads db 10)

;; Returns:
;; ({:thread-id "..."
;;   :subject "Project update"
;;   :email-count 8
;;   :participants #{"..."}
;;   :first-date #inst "..."
;;   :last-date #inst "..."}
;;  ...)
```

### Search Threads by Subject

```clojure
;; Find threads with "meeting" in the subject
(db/search-threads-by-subject db "meeting")

;; Returns thread IDs, then get full thread:
(->> (db/search-threads-by-subject db "meeting")
     (map #(db/get-thread-emails db %)))
```

### Conversation View

```clojure
;; Get thread formatted for conversation display
(db/get-conversation-view db "thread-id-here")

;; Returns:
;; ({:email {...}
;;   :index 0
;;   :date #inst "..."
;;   :from "alice@example.com"
;;   :subject "Re: Project"
;;   :snippet "Hi team, I wanted to discuss..."}
;;  ...)
```

### Manual Thread Queries

#### Find Orphaned Emails (No Thread)

```clojure
(d/q '[:find [(pull ?e [:email/subject :email/from]) ...]
       :where [?e :email/subject ?s]
              (not [?e :email/thread-id ?t])]
     db)
```

#### Count Emails per Thread

```clojure
(->> (d/q '[:find ?thread (count ?e)
            :where [?e :email/thread-id ?thread]]
          db)
     (sort-by second >)
     (take 20))
```

#### Find Longest Threads

```clojure
(->> (d/q '[:find ?thread (count ?e)
            :where [?e :email/thread-id ?thread]]
          db)
     (sort-by second >)
     (take 10))
```

#### Threads with Most Participants

```clojure
(->> (db/get-thread-ids db)
     (map (fn [tid] 
            (let [participants (db/get-thread-participants db tid)]
              {:thread-id tid
               :participant-count (count participants)
               :participants participants})))
     (sort-by :participant-count >)
     (take 10))
```

### Complete Example: Thread Explorer

```clojure
(defn explore-thread [db thread-id]
  (let [summary (db/get-thread-summary db thread-id)
        participants (db/get-thread-participants db thread-id)
        emails (db/get-thread-emails db thread-id)]
    
    (println "=== Thread:" (:subject summary) "===")
    (println "Emails:" (:email-count summary))
    (println "Participants:" (count participants))
    (println "Date range:" (:first-date summary) "->" (:last-date summary))
    (println "\nParticipants:")
    (doseq [p participants]
      (println "  " p))
    
    (println "\nConversation:")
    (doseq [email emails]
      (println "\n---")
      (println "From:" (:email/from email))
      (println "Date:" (:email/date email))
      (println "Subject:" (:email/subject email))
      (when-let [body (:email/body email)]
        (println "Body preview:" 
                 (subs body 0 (min 200 (count body))) 
                 "...")))))

;; Usage
(explore-thread db "some-thread-id")
```

---

## Aggregation & Analytics

### Top Senders

```clojure
;; Count emails per sender
(->> (d/q '[:find ?from (count ?e)
            :where [?e :email/from ?from]]
          db)
     (sort-by second >)
     (take 10))

;; Using helper
(db/top-senders db 10)
```

### Email Count by Date

```clojure
;; Group by month
(d/q '[:find ?month (count ?e)
       :where [?e :email/date ?d]
              [(.getMonth (.toInstant ?d)) ?month]]
     db)
```

### Thread Analysis

```clojure
;; Find threads with most emails
(d/q '[:find ?thread (count ?e)
       :where [?e :email/thread-id ?thread]
              [(some? ?thread)]]
     db)
```

### Label Distribution

```clojure
;; Count emails per label
(->> (d/q '[:find ?label (count ?e)
            :where [?e :email/labels ?label]]
          db)
     (sort-by second >))

;; Using helper
(db/top-labels db 20)
```

---

## Advanced Patterns

### Find Emails Missing Recipients

```clojure
(d/q '[:find [(pull ?e [:email/subject :email/from]) ...]
       :where [?e :email/subject ?s]
              (not [?e :email/to ?to])]
     db)
```

### Find Duplicates (same Message-ID)

```clojure
;; Shouldn't exist due to unique identity, but to check:
(d/q '[:find ?id (count ?e)
       :where [?e :email/id ?id]
       :group-by [?id]
       :having [(> (count ?e) 1)]]
     db)
```

### Emails with Attachments (HTML content)

```clojure
(d/q '[:find [(pull ?e [:email/subject :email/from]) ...]
       :where [?e :email/html ?html]
              [(some? ?html)]]
     db)
```

### Complex Date Filtering

```clojure
;; Emails from last 30 days
(let [thirty-days-ago (-> (java.util.Date.)
                          (.getTime)
                          (- (* 30 24 60 60 1000))
                          (java.util.Date.))]
  (d/q '[:find [(pull ?e [:email/subject :email/date]) ...]
         :in $ ?since
         :where [?e :email/date ?d]
                [(>= ?d ?since)]]
       db thirty-days-ago))
```

### Nested Pull Patterns

```clojure
;; Pull with nested conditions
(d/q '[:find [(pull ?e [:email/subject
                         :email/from
                         {:email/to [:email/from]}]) ...]
       :where [?e :email/subject]]
     db)
```

### Aggregation with Multiple Grouping

```clojure
;; Count by sender AND label
(d/q '[:find ?from ?label (count ?e)
       :where [?e :email/from ?from]
              [?e :email/labels ?label]]
     db)
```

---

## Helper Functions

The `astanova.db` namespace provides convenient helper functions:

### Available Helpers

```clojure
;; Query functions
(db/query-by-subject db "keyword")     ;; Search by subject
(db/query-by-sender db "addr@test.com") ;; By sender
(db/query-by-recipient db "addr@test.com") ;; By recipient
(db/query-by-label db "Important")      ;; By Gmail label
(db/query-by-date-range db start end)   ;; Date range

;; Thread functions
(db/get-thread-ids db)                  ;; Get all thread IDs
(db/get-thread-emails db thread-id)      ;; Get all emails in thread
(db/get-thread-participants db thread-id) ;; Get participants
(db/get-thread-summary db thread-id)    ;; Get thread summary
(db/get-threads-by-participant db addr) ;; Find threads by participant
(db/get-recent-threads db n)            ;; Most recent threads
(db/search-threads-by-subject db term)  ;; Search threads
(db/get-conversation-view db thread-id) ;; Formatted conversation

;; Entity lookup
(db/get-email-by-id db "<message-id>") ;; Single email by ID

;; Analytics
(db/count-emails db)       ;; Total count
(db/get-all-labels db)     ;; All unique labels
(db/get-all-senders db)    ;; All unique senders
(db/top-senders db 10)     ;; Top 10 senders
(db/top-labels db 10)      ;; Top 10 labels
(db/search-emails db "term") ;; Full-text search
(db/recent-emails db 20)   ;; 20 most recent
```

### Example Usage

```clojure
;; Connect and query
(let [conn (db/create-conn "emails.db")
      db (d/db conn)]
  
  ;; Get overview
  (println "Total emails:" (db/count-emails db))
  (println "Top senders:" (db/top-senders db 5))
  
  ;; Search for specific emails
  (let [results (db/query-by-subject db "invoice")]
    (println "Found" (count results) "emails about invoices"))
  
  ;; Clean up
  (db/close-conn conn))
```

---

## Performance Tips

### 1. Use Database Snapshots

```clojure
;; Good: immutable snapshot
(def db (d/db conn))
(d/q '[:find ...] db)

;; Avoid: querying connection directly (gets new snapshot each time)
(d/q '[:find ...] (d/db conn))
```

### 2. Limit Pulled Attributes

```clojure
;; Good: only pull what you need
(d/q '[:find [(pull ?e [:email/subject :email/from]) ...] ...] db)

;; Avoid: pulling everything
(d/q '[:find [(pull ?e [*]) ...] ...] db)
```

### 3. Use Entity API for Single Records

```clojure
;; Fast: direct lookup by unique identity
(d/entity db [:email/id "<message-id>"])

;; Slower: query then pull
(d/q '[:find (pull ?e [*]) . :where [?e :email/id "<message-id>"]] db)
```

### 4. Add Indexes for Common Queries

Consider adding indexes to your schema:

```clojure
;; In db.clj build-email-schema
:email/from  {:db/valueType :db.type/string
              :db/cardinality :db.cardinality/one
              :db/index true}  ;; Add index

:email/date  {:db/valueType :db.type/instant
              :db/cardinality :db.cardinality/one
              :db/index true}  ;; Add index
```

### 5. Batch Operations

```clojure
;; Good: single query with multiple conditions
(d/q '[:find [(pull ?e [:email/subject]) ...]
       :where (or [?e :email/labels "Important"]
                  [?e :email/labels "Starred"])])

;; Avoid: multiple separate queries
(map #(db/query-by-label db %) ["Important" "Starred"])
```

---

## Real-World Examples

### Example 1: Weekly Digest

```clojure
(defn weekly-digest [db]
  (let [week-ago (-> (java.util.Date.)
                     (.getTime)
                     (- (* 7 24 60 60 1000))
                     (java.util.Date.))]
    (->> (d/q '[:find [(pull ?e [:email/subject :email/from :email/date]) ...]
               :in $ ?since
               :where [?e :email/date ?d]
                      [(>= ?d ?since)]]
             db week-ago)
       (sort #(compare (:email/date %1) (:email/date %2)))))))
```

### Example 2: Find Mailing List Emails

```clojure
(defn find-mailing-lists [db]
  (d/q '[:find ?from (count ?e)
         :where [?e :email/from ?from]
                [(clojure.string/includes? ?from "noreply")]
         :group-by [?from]
         :having [(> (count ?e) 10)]]
       db))
```

### Example 3: Conversation View

```clojure
(defn get-conversation [db thread-id]
  (db/get-conversation-view db thread-id))

;; Or get full thread with all details
(defn get-full-thread [db thread-id]
  (let [emails (db/get-thread-emails db thread-id)
        summary (db/get-thread-summary db thread-id)]
    {:summary summary
     :emails emails}))
```

### Example 4: Search with Pagination

```clojure
(defn paginated-search [db search-term page-size page-num]
  (let [offset (* page-size (dec page-num))
        results (db/search-emails db search-term)]
    {:total (count results)
     :page (take page-size (drop offset results))}))
```

### Example 5: Export to EDN

```clojure
(defn export-emails [db output-file]
  (let [emails (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :email/subject]]
                     db)]
    (spit output-file (pr-str emails))))
```

### Example 6: Analytics Dashboard

```clojure
(defn email-analytics [db]
  {:total (db/count-emails db)
   :top-senders (db/top-senders db 10)
   :top-labels (db/top-labels db 10)
   :date-range (d/q '[:find (min ?d) (max ?d)
                      :where [?e :email/date ?d]]
                    db)
   :with-attachments (d/q '[:find (count ?e)
                            :where [?e :email/html ?h]
                                   [(some? ?h)]]
                          db)})
```

### Example 7: Thread Analysis

```clojure
(defn analyze-threads [db]
  (let [all-threads (db/get-thread-ids db)
        thread-stats (for [tid all-threads]
                      (let [summary (db/get-thread-summary db tid)]
                        {:thread-id tid
                         :subject (:subject summary)
                         :email-count (:email-count summary)
                         :participant-count (count (:participants summary))
                         :duration-days (when (and (:first-date summary) 
                                                   (:last-date summary))
                                          (-> (- (.getTime (:last-date summary))
                                                (.getTime (:first-date summary)))
                                              (/ (* 24 60 60 1000))
                                              Math/ceil))}))]
    {:total-threads (count all-threads)
     :avg-emails-per-thread (let [counts (map :email-count thread-stats)]
                             (/ (reduce + counts) (count counts)))
     :longest-threads (->> thread-stats
                          (sort-by :email-count >)
                          (take 10))
     :most-participants (->> thread-stats
                          (sort-by :participant-count >)
                          (take 10))}))

;; Usage
(analyze-threads db)
```

### Example 8: Find Active Conversations

```clojure
(defn find-active-conversations [db days-threshold]
  (let [cutoff (-> (java.util.Date.)
                   (.getTime)
                   (- (* days-threshold 24 60 60 1000))
                   (java.util.Date.))
        recent-threads (db/get-recent-threads db 50)]
    (filter (fn [thread]
              (and (:last-date thread)
                   (.after (:last-date thread) cutoff)))
            recent-threads)))

;; Find conversations with activity in last 7 days
(find-active-conversations db 7)
```

---

## CLI Usage

Don't forget you can also query from the command line:

```bash
# Search by subject
./takeout -d emails.db query -s "meeting"

# Filter by sender
./takeout -d emails.db query -f "alice@example.com"

# Filter by date
./takeout -d emails.db query --since 2024-01-01 --before 2024-12-31

# Limit results
./takeout -d emails.db query -s "invoice" -n 50

# Export results as JSON
./takeout -d emails.db query -s "meeting" --format json

# Get statistics
./takeout -d emails.db stats
```

---

## Troubleshooting

### Query Returns Empty Results

```clojure
;; Debug: check if data exists
(d/q '[:find (count ?e) . :where [?e :email/subject]] db)

;; Debug: check attribute values
(d/q '[:find ?s . :where [?e :email/subject ?s]] db)
```

### Slow Queries

- Add `:db/index true` to schema for frequently queried fields
- Use `(d/db conn)` to get fresh snapshot
- Limit pulled attributes in `:find` clause
- Check if date comparisons are using proper types

### Memory Issues

```clojure
;; Bad: pulls all emails into memory
(d/q '[:find [(pull ?e [*]) ...] ...] db)

;; Good: paginate or limit
(d/q '[:find [(pull ?e [:email/subject]) ...]
       ...]
     db)
```

---

## Next Steps

- Explore the `astanova.db` namespace for more helpers
- Check Clojure's `clojure.string` for text processing
- Use `portal` or `cider-inspect` to explore query results
- Consider adding full-text indexing for large datasets

```clojure
;; Quick REPL exploration
(tap> (db/top-senders db 5))
(tap> (db/search-emails db "important"))
```

Happy querying!
