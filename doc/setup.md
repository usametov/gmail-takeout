# Setup

This guide covers the runtime prerequisites, the CLI entry points, and the
`~/.secrets.edn` contract for `gmail-takeout`.

## Prerequisites

| Requirement | Needed for |
|-------------|------------|
| Java 21+ | Clojure/Datalevin |
| Clojure CLI 1.12+ | `clojure -M:run-m`, tests, build |
| Babashka (`bb`) 1.x | `scripts/*.clj`, especially the ontology classifier |
| Google Takeout MBOX export | ingestion |
| `gws` (Google Workspace CLI) | optional Gmail enrichment scripts |
| `yt-dlp` | optional YouTube metadata in `fetch-content.clj` |
| `clj-kondo` | optional linting |

## Running the CLI from a source checkout

The README and older docs use `./takeout`; that wrapper is not committed to the
repository. From a fresh checkout use the `:run-m` alias:

```bash
clojure -M:run-m --help
clojure -M:run-m -d emails.db ingest ~/Takeout/Mail/Inbox.mbox
clojure -M:run-m -d emails.db query -l "Important" -n 10 --format table
```

Equivalent form:

```bash
clojure -M -m astanova.takeout -d emails.db stats
```

Once a wrapper is added, `./takeout …` is simply shorthand for the same thing.
The full test suite currently depends on fixtures that are not committed, so
`clojure -T:build ci` will fail until that is fixed; use `-M:run-m` for now.

## Ontology data layout

The ontology pipeline data now lives in [`ontology/`](../ontology/README.md):

```
ontology/
  config.json            # categories, allow/stop lists, canonical aliases
  raw_candidates.json    # candidate compiler output
  ontology_cache.json    # classified/normalized cache (source of truth)
  ontology_audit.jsonl   # runtime audit log (ignored by git)
```

`scripts/ontology_classifier.clj` auto-detects this directory, so no
`--base-dir` is needed when running from the repo. Resolution order is:

1. `--base-dir`
2. `GMAILS_BASE_DIR`
3. `<repo>/ontology` (or `./ontology` when the script path cannot be resolved)
4. `~/code/antigravity-gmail-project`
5. `~/code/gmails`
6. current directory

The initial contents were copied from the antigravity project; the originals
were left in place. Once you are satisfied with the new location, you can
archive the antigravity copies. Future classifier runs default to
`<repo>/ontology`, so old and new caches will diverge if you keep running the
script against the old directory.

## `~/.secrets.edn`

Secrets are read from a single EDN map in the user's home directory. The file is
optional for most commands and is only needed for live LLM calls.

### Required key

For `scripts/ontology_classifier.clj`, only one key is required:

```clojure
{:deepseek-api-key "sk-..."}
```

### Optional keys

```clojure
{:deepseek-api-key "sk-..."
 :deepseek-base    "https://api.deepseek.com"  ; optional; default is this URL
 :deepseek-model   "deepseek-v4-flash"}        ; optional; default is this model
```

- `:deepseek-base` — an OpenAI-compatible base URL. Set this for a gateway, a
  proxy, or a local model server (for example `http://localhost:11434/v1`).
- `:deepseek-model` — the model id. The script defaults to
  `deepseek-v4-flash`; if that model is not available on your account, set
  `:deepseek-model "deepseek-chat"` (or whatever your endpoint exposes).

Extra keys are ignored by `gmail-takeout`, so the same file can hold
configuration for other projects (for example `:goszakupki-api-key`).

### Precedence

| Setting | Highest → lowest |
|---------|------------------|
| API key | `DEEPSEEK_API_KEY` → `~/.secrets.edn` `:deepseek-api-key` |
| Base URL | `--base-url` → `DEEPSEEK_BASE` / `DEEPSEEK_BASE_URL` → `:deepseek-base` → `config.json base_url` → `https://api.deepseek.com` |
| Model | `--model` → `DEEPSEEK_MODEL` → `:deepseek-model` → `config.json model` → `deepseek-v4-flash` |

An environment variable always wins over the secrets file.

### Permissions and format

The file must be valid EDN (not JSON) and should be readable only by you:

```bash
chmod 600 ~/.secrets.edn
stat -c '%a %n' ~/.secrets.edn    # expect 600
```

Validate the structure without printing the secret:

```bash
bb -e '(println (sort (map name (keys (clojure.edn/read-string
         (slurp (str (System/getProperty "user.home") "/.secrets.edn")))))))'
```

Expected output includes `deepseek-api-key`.

### Safe creation without shell history

```bash
umask 077
read -rsp 'DeepSeek API key: ' DEEPSEEK_API_KEY; echo
printf '{:deepseek-api-key "%s"\n :deepseek-base "https://api.deepseek.com"}\n' \
  "$DEEPSEEK_API_KEY" > ~/.secrets.edn
unset DEEPSEEK_API_KEY
chmod 600 ~/.secrets.edn
```

## What does *not* belong in `~/.secrets.edn`

| Credential | How it is handled |
|------------|-------------------|
| Gmail OAuth / Google account | `gws` stores its own credentials in the OS keyring. `map_gmail_ids.clj`, `fetch-bodies.clj`, and `update-label-pipeline.clj` use `gws`, not this file. |
| GitHub token | Optional `GITHUB_TOKEN` environment variable for `scripts/top-repos.clj`. |
| YouTube / arxiv / web access | Public HTTP; no key. |
| Datalevin / database | No credentials. |

Only the ontology classifier talks to an LLM. Ingestion, querying, reporting,
and the Gmail enrichment scripts do not read `~/.secrets.edn`.

## Smoke tests

The ontology classifier works without a key in validation modes:

```bash
bb scripts/ontology_classifier.clj --check
bb scripts/ontology_classifier.clj --dry-run --limit 1
```

A live one-term run validates the key and model end to end. It writes that one
term to the ontology cache, so back up the cache first if it is important:

```bash
cp ontology/ontology_cache.json ontology/ontology_cache.json.bak
bb scripts/ontology_classifier.clj --limit 1
```

To check the key without touching the ontology cache, make a tiny direct call:

```bash
bb -e '
(require (quote [babashka.http-client :as http])
         (quote [cheshire.core :as json])
         (quote [clojure.edn :as edn]))
(let [s (edn/read-string (slurp (str (System/getProperty "user.home") "/.secrets.edn")))
      r (http/post "https://api.deepseek.com/chat/completions"
                   {:headers {"Authorization" (str "Bearer " (:deepseek-api-key s))
                              "Content-Type" "application/json"}
                    :body (json/generate-string {:model "deepseek-v4-flash"
                                                 :messages [{:role "user" :content "Reply JSON {\"ok\":true}"}]
                                                 :max_tokens 20
                                                 :response_format {:type "json_object"}})
                    :throw false})]
  (println "HTTP" (:status r)))
'
```

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|---------------------|
| `DeepSeek API key is not configured` | Add `:deepseek-api-key` or export `DEEPSEEK_API_KEY`. Only live runs need it. |
| HTTP 401 | Invalid/revoked key, or wrong gateway. |
| HTTP 402 | Account balance/quota. |
| HTTP 404 mention of a model | Model id not available. Set `:deepseek-model` or `DEEPSEEK_MODEL` to a supported id (for example `deepseek-chat`). |
| `Could not read secrets file` / EDN parse error | The file must be a valid EDN map; check quoting and braces. |
| `CHECK FAILED` from `--check` | Cache has invalid category values or category conflicts; fix or run `--normalize-cache` after reviewing. |
| Nothing is sent to the API | All candidates are cached or filtered. Use `--dry-run` to inspect the diff. |

## Related docs

- [Ontology Classifier](ontology-classifier.md)
- [Ingest Guide](ingest-guide.md)
- [CLI Query Guide](cli-query-guide.md)
- [Integrations](integrations.md)
