# MBOX Ingestion Guide - Creating and Populating Your Email Database

Complete guide to ingesting Google Takeout MBOX files into Datalevin database using `astanova.takeout`.

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Quick Start](#quick-start)
4. [CLI Usage](#cli-usage)
5. [Programmatic API](#programmatic-api)
6. [Batch Processing](#batch-processing)
7. [Advanced Configuration](#advanced-configuration)
8. [Verification & Testing](#verification--testing)
9. [Troubleshooting](#troubleshooting)
10. [Best Practices](#best-practices)

---

## Overview

The ingestion process:
1. **Parse** MBOX files using Apache Mime4j
2. **Extract** email metadata (subject, from, to, date, body, labels)
3. **Transform** into Datalevin entities
4. **Transact** into Datalevin with automatic deduplication

### Key Features
- ✅ Automatic deduplication (via Message-ID)
- ✅ Batch processing for large files
- ✅ Gmail label extraction
- ✅ HTML to plain text conversion
- ✅ Multipart MIME handling
- ✅ Resume-safe (unique identities)

---

## Prerequisites

### Install the Tool

```bash
# Clone the repository
git clone <repo-url>
cd takeout

# Build the project (if needed)
clojure -T:build uber
```

### Prepare Your MBOX Files

Google Takeout exports:
1. Go to [Google Takeout](https://takeout.google.com/)
2. Select **Mail** 
3. Choose **MBOX format**
4. Download and extract the ZIP
5. You'll have a folder with `.mbox` files

Example structure:
```
Takeout/
└── Mail/
    ├── All mail Including Spam and Trash.mbox
    ├── Inbox.mbox
    ├── Sent.mbox
    ├── Starred.mbox
    └── Label1.mbox
```

---

## Quick Start

### Option 1: Using the CLI (Recommended)

```bash
# Ingest all MBOX files from a directory
./takeout -d emails.db ingest /path/to/Takeout/Mail/

# Ingest specific files
./takeout -d emails.db ingest file1.mbox file2.mbox

# Check the results
./takeout -d emails.db stats
```

### Option 2: Using the API (REPL/Scripting)

```clojure
(require '[astanova.db :as db])
(require '[astanova.ingest :as ingest])

;; Create database and ingest
(let [conn (db/create-conn "emails.db")]
  (try
    (ingest/ingest-mbox-files! conn ["/path/to/file.mbox"] "google-takeout")
    (finally (db/close-conn conn))))
```

---

## CLI Usage

### Basic Ingest Command

```bash
# Syntax
./takeout -d <database-path> ingest [options] <file-or-directory>...

# Examples
./takeout -d emails.db ingest ~/Takeout/Mail/
./takeout -d emails.db ingest file1.mbox file2.mbox
```

### CLI Options

| Option | Description | Default |
|--------|-------------|---------|
| `-d, --db PATH` | Database directory path | `emails.db` |
| `-s, --source S` | Source label (for identification) | `google-takeout` |
| `-b, --batch N` | Transaction batch size | `100` |
| `-h, --help` | Show help | - |

### Examples

#### Ingest Single File
```bash
./takeout -d my-emails.db ingest ~/Mail/Inbox.mbox
```

#### Ingest Multiple Files
```bash
./takeout -d my-emails.db ingest ~/Mail/Inbox.mbox ~/Mail/Sent.mbox
```

#### Ingest Directory (All MBOX Files)
```bash
./takeout -d my-emails.db ingest ~/Takeout/Mail/
```

#### Custom Source Label
```bash
./takeout -d my-emails.db ingest -s "gmail-backup" ~/Mail/
```

#### Adjust Batch Size (for large files)
```bash
# Smaller batches for memory-constrained systems
./takeout -d my-emails.db ingest -b 50 ~/Mail/large-file.mbox

# Larger batches for faster ingestion
./takeout -d my-emails.db ingest -b 500 ~/Mail/
```

### Output Example

```
$ ./takeout -d emails.db ingest ~/Takeout/Mail/

Ingesting 5 file(s) (source: google-takeout, batch: 100)...
  All mail Including Spam and Trash.mbox:  15420 emails  155 txs
  Inbox.mbox:                               3245 emails   33 txs
  Sent.mbox:                               2876 emails   29 txs
  Starred.mbox:                             432 emails    5 txs
  Label1.mbox:                             1234 emails   13 txs
Done.
```

---

## Programmatic API

### Core Functions

#### 1. Create Database Connection

```clojure
(require '[astanova.db :as db])

;; Create or open database
(def conn (db/create-conn "emails.db"))

;; Get database schema
(db/build-email-schema)
;; => {:email/id {:db/valueType :db.type/string ...} ...}

;; Close when done
(db/close-conn conn)
```

#### 2. Ingest Single MBOX File

```clojure
(require '[astanova.ingest :as ingest])

;; Ingest one file
(let [conn (db/create-conn "emails.db")
      stats (ingest/ingest-mbox! conn 
                                "/path/to/file.mbox" 
                                "google-takeout"
                                :batch-size 100)]
  (println "Ingested:" stats)
  ;; => {:tx-count 15, :email-count 1423}
  (db/close-conn conn))
```

#### 3. Ingest Multiple Files

```clojure
;; Ingest multiple files at once
(let [conn (db/create-conn "emails.db")
      files ["/path/to/file1.mbox"
             "/path/to/file2.mbox"
             "/path/to/file3.mbox"]
      stats (ingest/ingest-mbox-files! conn 
                                      files 
                                      "google-takeout"
                                      :batch-size 100)]
  (println "Total stats:" stats)
  ;; => {:tx-count 45, :email-count 4521}
  (db/close-conn conn))
```

#### 4. Ingest Directory

```clojure
(require '[clojure.java.io :as io])

;; Find all MBOX files in directory
(let [dir (io/file "/path/to/Mail/")
      mbox-files (filter #(-> % .getName (.endsWith ".mbox")) 
                        (.listFiles dir))]
  
  (with-open [_ nil]  ;; connection management
    (let [conn (db/create-conn "emails.db")]
      (ingest/ingest-mbox-files! conn 
                                  (map str mbox-files) 
                                  "google-takeout"))))
```

### Complete Example Script

```clojure
(ns my-ingest-script
  (:require [astanova.db :as db]
            [astanova.ingest :as ingest]
            [clojure.java.io :as io]))

(defn ingest-google-takeout
  "Ingest all MBOX files from Google Takeout export."
  [takeout-dir db-path]
  (let [dir (io/file takeout-dir)
        mbox-files (when (.isDirectory dir)
                     (->> (.listFiles dir)
                          (filter #(-> % .getName (.endsWith ".mbox")))))
        conn (db/create-conn db-path)]
    
    (println "Found" (count mbox-files) "MBOX files")
    
    (try
      (doseq [file mbox-files]
        (println "Ingesting" (.getName file) "...")
        (let [stats (ingest/ingest-mbox! conn 
                                        (.getAbsolutePath file) 
                                        "google-takeout")]
          (println "  =>" (:email-count stats) "emails")))
      
      (println "Ingestion complete!")
      
      (finally
        (db/close-conn conn)))))

;; Run it
(ingest-google-takeout "/Users/me/Takeout/Mail" "emails.db")
```

---

## Batch Processing

### Why Batch?

- **Memory management**: Process large files in chunks
- **Transaction efficiency**: Group operations
- **Progress tracking**: See incremental progress
- **Error isolation**: Failed batch doesn't lose everything

### Default Batch Size

The default batch size is **100 emails per transaction**.

### Tuning Batch Size

```clojure
;; Smaller batches (safer for large files, less memory)
(ingest/ingest-mbox! conn file "source" :batch-size 50)

;; Larger batches (faster, more memory)
(ingest/ingest-mbox! conn file "source" :batch-size 500)
```

### Batch Processing Internals

```clojure
;; The ingest function partitions emails into batches
(defn- partition-batches [coll n]
  (partition n n nil coll))

;; Each batch is transacted separately
(doseq [batch batches]
  (d/transact! conn batch))
```

---

## Advanced Configuration

### Custom Schema

Modify `db.clj` to add custom attributes:

```clojure
(defn build-email-schema
  []
  {:email/id          {:db/valueType :db.type/string
                      :db/unique :db.unique/identity}
   :email/subject     {:db/valueType :db.type/string}
   ;; Add custom fields:
   :email/custom-flag {:db/valueType :db.type/boolean
                      :db/cardinality :db.cardinality/one}
   :email/size        {:db/valueType :db.type/long
                      :db/cardinality :db.cardinality/one}})
```

### Deduplication

Emails are automatically deduplicated based on `Message-ID`:

```clojure
;; Schema enforces uniqueness
:email/id {:db/unique :db.unique/identity}

;; If you re-ingest the same email, it updates rather than duplicates
```

### Handle Parsing Errors

The parser is resilient, but you can add error handling:

```clojure
(require '[astanova.parse :as parse])

;; Parse with error handling
(try
  (parse/parse-mbox "/path/to/file.mbox")
  (catch Exception e
    (println "Parse error:" (.getMessage e))))
```

---

## Verification & Testing

### 1. Check Database Statistics

```bash
# Using CLI
./takeout -d emails.db stats
```

Output:
```
Database statistics:

  Total emails:     23107
  Date range:       2018-03-15 → 2024-07-07
  Top labels:
    INBOX                      8234
    Sent Mail                  4521
    Starred                    432
    Important                  1234
  Top senders:
    newsletter@company.com     345
    alice@example.com          234
    bob@test.org               187
```

### 2. Verify with Queries

```clojure
(require '[datalevin.core :as d])
(require '[astanova.db :as db])

(let [conn (db/create-conn "emails.db")
      db (d/db conn)]
  
  ;; Count total emails
  (println "Total:" 
           (d/q '[:find (count ?e) .
                  :where [?e :email/subject]]
                db))
  
  ;; Sample a few emails
  (println "Sample:"
           (d/q '[:find [(pull ?e [:email/subject :email/from]) ...]
                  :where [?e :email/subject]
                  :limit 5]
                db))
  
  (db/close-conn conn))
```

### 3. Test Deduplication

```clojure
;; Ingest same file twice
(let [conn (db/create-conn "test.db")]
  ;; First ingest
  (ingest/ingest-mbox! conn "file.mbox" "source")
  (let [count1 (d/q '[:find (count ?e) . :where [?e :email/subject]] 
                    (d/db conn))]
    
    ;; Second ingest (should not increase count)
    (ingest/ingest-mbox! conn "file.mbox" "source")
    (let [count2 (d/q '[:find (count ?e) . :where [?e :email/subject]] 
                      (d/db conn))]
      (println "Before:" count1 "After:" count2 "Same?" (= count1 count2))))
  
  (db/close-conn conn))
```

### 4. Validate Email Structure

```clojure
;; Check if emails have required fields
(let [conn (db/create-conn "emails.db")
      db (d/db conn)]
  
  ;; Emails missing subject
  (println "Missing subject:"
           (d/q '[:find (count ?e)
                  :where [?e :email/id ?id]
                         (not [?e :email/subject ?s])]
                db))
  
  ;; Emails missing date
  (println "Missing date:"
           (d/q '[:find (count ?e)
                  :where [?e :email/id ?id]
                         (not [?e :email/date ?d])]
                db))
  
  (db/close-conn conn))
```

---

## Troubleshooting

### Issue: "No .mbox files found"

```bash
# Check if files exist and have correct extension
ls -la /path/to/directory/
# => file.mbox (should have .mbox extension)

# Use absolute paths
./takeout -d emails.db ingest /absolute/path/to/file.mbox
```

### Issue: Out of Memory

```bash
# Reduce batch size
./takeout -d emails.db ingest -b 50 large-file.mbox

# Or increase JVM memory
export JVM_OPTS="-Xmx4g"
clojure -M -m astanova.takeout ingest ...
```

### Issue: Parse Errors

```clojure
;; Test parse individually
(require '[astanova.parse :as parse])

;; Parse with debugging
(let [emails (parse/parse-mbox "/path/to/file.mbox")]
  (println "Parsed" (count emails) "emails")
  (println "First email:" (first emails)))
```

### Issue: Database Locked

```bash
# Make sure no other process is using the database
lsof emails.db/

# Delete lock file if needed (caution!)
rm -f emails.db/lock
```

### Issue: Slow Ingestion

- Increase batch size: `-b 500`
- Use faster disk (SSD)
- Close other applications
- Check if antivirus is scanning files

---

## Best Practices

### 1. Start Small

```bash
# Test with one small file first
./takeout -d test.db ingest small-file.mbox
./takeout -d test.db stats
```

### 2. Use Meaningful Source Labels

```bash
# Good: descriptive
./takeout -d emails.db ingest -s "gmail-2024-july" ~/Mail/

# Bad: generic
./takeout -d emails.db ingest -s "test" ~/Mail/
```

### 3. Backup Database

```bash
# Backup before re-ingesting
cp -r emails.db emails.db.backup

# Or use version control on database path
```

### 4. Monitor Progress

```clojure
;; Add logging to ingestion
(let [conn (db/create-conn "emails.db")
      emails (parse/parse-mbox "large-file.mbox")
      batches (partition 100 emails)]
  
  (doseq [i (range)]
    (let [batch (nth batches i nil)]
      (when batch
        (println "Processing batch" (inc i))
        (ingest/ingest-emails! conn batch "file.mbox" "source")))))
```

### 5. Validate After Ingestion

```bash
# Always check stats after ingestion
./takeout -d emails.db stats

# Spot check some emails
./takeout -d emails.db query -n 5 --format edn
```

### 6. Handle Large Archives

```bash
# For very large Takeout exports:
# 1. Extract files individually
# 2. Ingest in batches
# 3. Verify each batch

for file in ~/Takeout/Mail/*.mbox; do
  echo "Ingesting $file..."
  ./takeout -d emails.db ingest "$file"
  ./takeout -d emails.db stats | grep "Total emails"
done
```

---

## Complete Example: Ingest Google Takeout

```bash
#!/bin/bash
# Script: ingest-takeout.sh

TAKEOUT_DIR="$1"
DB_PATH="emails.db"

if [ -z "$TAKEOUT_DIR" ]; then
  echo "Usage: $0 <takeout-mail-directory>"
  exit 1
fi

echo "=== Google Takeout Email Ingestion ==="
echo "Source: $TAKEOUT_DIR"
echo "Database: $DB_PATH"
echo ""

# Check if directory exists
if [ ! -d "$TAKEOUT_DIR" ]; then
  echo "Error: Directory not found: $TAKEOUT_DIR"
  exit 1
fi

# Count MBOX files
MBOX_COUNT=$(find "$TAKEOUT_DIR" -name "*.mbox" | wc -l)

echo "Found $MBOX_COUNT MBOX files"
echo ""

# Ingest all files
./takeout -d "$DB_PATH" ingest "$TAKEOUT_DIR"

# Show final statistics
echo ""
echo "=== Final Statistics ==="
./takeout -d "$DB_PATH" stats

echo ""
echo "=== Ingestion Complete ==="
```

---

## API Reference

### `astanova.db`

```clojure
(build-email-schema)            ;; Returns schema map
(create-conn db-path)            ;; Opens/creates database
(close-conn conn)                ;; Closes connection
(get-email-attrs schema)         ;; Returns attribute set
```

### `astanova.ingest`

```clojure
(ingest-emails! conn emails mbox-file source & opts)  ;; Ingest parsed emails
(ingest-mbox! conn mbox-path source & opts)            ;; Ingest single MBOX
(ingest-mbox-files! conn mbox-dir source & opts)      ;; Ingest multiple files
```

Options:
- `:batch-size` (default: 100)

### `astanova.parse`

```clojure
(parse-mbox mbox-path)                 ;; Returns lazy seq of email maps
(parse-raw-message raw-msg)            ;; Parse single raw message
(mbox-messages mbox-path)              ;; Low-level: raw message seq
```

---

## Next Steps

After ingesting your emails, you can:

1. **Query the database** - See `query-guide.md`
2. **Export emails** - `./takeout -d emails.db export output.json`
3. **Analyze statistics** - `./takeout -d emails.db stats`
4. **Build applications** - Use the API to create custom tools

```clojure
;; Quick REPL exploration
(require '[astanova.db :as db])
(require '[datalevin.core :as d])

(def conn (db/create-conn "emails.db"))
(def db (d/db conn))

;; Your queries here...

(db/close-conn conn)
```

Happy ingesting!