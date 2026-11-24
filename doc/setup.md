# Setup

This guide covers the runtime prerequisites, the CLI entry points, the
`~/.secrets.edn` contract, and the external `gws` CLI for `gmail-takeout`.

## Prerequisites

| Requirement | Needed for |
|-------------|------------|
| Java 21+ | Clojure/Datalevin |
| Clojure CLI 1.12+ | `clojure -M:run-m`, tests, build |
| Babashka (`bb`) 1.x | `scripts/*.clj`, especially the ontology classifier |
| Google Takeout MBOX export | ingestion |
| [`gws`](https://github.com/googleworkspace/cli) | Gmail ID mapping, body fetching, attachment retrieval |
| `yt-dlp` | optional YouTube metadata in `fetch-content.clj` |
| `clj-kondo` | optional linting |

## Running the CLI from a source checkout

The repository includes `./takeout`, a thin wrapper around `clojure -M:run-m`:

```bash
./takeout --help
./takeout -d emails.db ingest ~/Takeout/Mail/Inbox.mbox
./takeout -d emails.db query -l "Important" -n 10 --format table
```

Equivalent forms if you prefer not to use the wrapper:

```bash
clojure -M:run-m -d emails.db stats
clojure -M -m astanova.takeout -d emails.db stats
```

The test suite and `clojure -T:build ci` are expected to pass from a fresh
checkout; the fixtures live under `test/fixtures/`.

## `gws` (Google Workspace CLI)

`gws` is the Gmail API bridge used by the enrichment pipeline. It is **not**
needed for ingestion, querying, reporting, or the ontology classifier, but it is
required for:

- `scripts/map_gmail_ids.clj` — RFC822 Message-ID → Gmail internal ID
- `scripts/fetch-bodies.clj` — fetch full bodies via `gws gmail +read`
- `scripts/update-label-pipeline.clj` — the four-step enrichment pipeline
- attachment retrieval via `gws gmail users messages attachments get`

Project: **https://github.com/googleworkspace/cli**

> `gws` is not an officially supported Google product. It reads Google's
> Discovery Service at runtime, so its command surface follows the live APIs.

### Install

The recommended installation is a pre-built binary from
[GitHub Releases](https://github.com/googleworkspace/cli/releases). This avoids
npm/global-prefix permission problems entirely.

```bash
# Linux x86_64 — adjust TARGET and VERSION for your platform/release
VERSION=v0.22.5
TARGET=x86_64-unknown-linux-gnu
ARCHIVE="google-workspace-cli-${TARGET}.tar.gz"

TMP="$(mktemp -d)"
cd "$TMP"
curl -fsSLO "https://github.com/googleworkspace/cli/releases/download/${VERSION}/${ARCHIVE}"
curl -fsSLO "https://github.com/googleworkspace/cli/releases/download/${VERSION}/${ARCHIVE}.sha256"
sha256sum -c "${ARCHIVE}.sha256"
tar -xzf "$ARCHIVE"

mkdir -p "$HOME/.local/bin"
install -m 0755 gws "$HOME/.local/bin/gws"
cd / && rm -rf "$TMP"

gws --version
```

Other targets include `aarch64-unknown-linux-gnu`,
`x86_64-apple-darwin`, and `aarch64-apple-darwin`. Use
`shasum -a 256 -c` instead of `sha256sum -c` on macOS.

Convenience alternatives:

```bash
# Node.js 18+
npm install -g @googleworkspace/cli

# macOS / Linux
brew install googleworkspace-cli

# From source
cargo install --git https://github.com/googleworkspace/cli --locked

# Nix
nix run github:googleworkspace/cli
```

Verify the binary and PATH:

```bash
command -v gws
gws --version
```

If `gws` is still “not found”, check your shell PATH and the npm global prefix:

```bash
npm config get prefix
echo "$PATH"
# e.g. add to ~/.bashrc if npm puts binaries in ~/.npm-global/bin:
export PATH="$HOME/.npm-global/bin:$PATH"
```

If `npm config get prefix` points at a system directory such as `/usr`, a global
install may need root. Prefer the release binary or Homebrew, or install into a
user prefix without changing the global npm config:

```bash
npm install -g --prefix "$HOME/.npm-global" @googleworkspace/cli
export PATH="$HOME/.npm-global/bin:$PATH"
```

Do **not** work around the permission error with `sudo npm install -g`; it
creates root-owned files that later installs cannot replace. If a failed install
left an empty `/usr/lib/node_modules/@googleworkspace/cli`, remove it with
`sudo rm -rf /usr/lib/node_modules/@googleworkspace/cli` (and the empty
`@googleworkspace` directory) or simply ignore it and use the binary/user-prefix
install.

### Authenticate

`gws` stores its OAuth credentials separately from `~/.secrets.edn` (encrypted
with the OS keyring by default).

Fastest path if `gcloud` is installed:

```bash
gws auth setup
gws auth login
```

Without `gcloud`, use the manual OAuth flow:

1. Create or select a Google Cloud project:
   <https://console.cloud.google.com/projectselector2/home/dashboard>
2. Enable the Gmail API:
   <https://console.cloud.google.com/apis/library/gmail.googleapis.com>
3. Configure the OAuth consent screen as **External**, and add your Google
   account under **Test users**:
   <https://console.cloud.google.com/apis/credentials/consent>
4. Create an **OAuth client ID** of type **Desktop app** and download the JSON:
   <https://console.cloud.google.com/apis/credentials>
5. Install the client secret where `gws` expects it:

   ```bash
   mkdir -p ~/.config/gws
   mv ~/Downloads/client_secret_*.json ~/.config/gws/client_secret.json
   ```

6. Run the login flow and check the result:

   ```bash
   gws auth login -s gmail
   gws auth status
   ```

`gws auth status` must report an authenticated method, not
`"auth_method": "none"` / `"storage": "none"`.

For an unverified/test OAuth app, request only the Gmail scope needed by this
project:

```bash
gws auth login -s gmail
```

The generic `recommended` preset has 85+ scopes and will fail the ~25-scope limit
for unverified apps. Add `drive` only if you also want attachment/Drive helpers:

```bash
gws auth login -s gmail,drive
```

Verify Gmail access:

```bash
gws gmail users messages list --params '{"userId":"me","maxResults":1}'
```

Then run the project scripts, for example:

```bash
./takeout -d emails.db query -l "trading" --format edn \
  | bb scripts/map_gmail_ids.clj -o map-ids.edn --no-dry-run
```

(or `clojure -M:run-m …` if the `./takeout` wrapper is not present).

### Troubleshooting

| Symptom | Fix |
|---------|-----|
| `gws: command not found` | Install it, or fix `PATH`/npm global prefix as above. |
| npm `EACCES` while renaming `/usr/lib/node_modules/@googleworkspace/cli` | npm’s global prefix is root-owned. Don’t use sudo npm; use the release binary, or `npm install -g --prefix "$HOME/.npm-global" @googleworkspace/cli`. Remove a stale empty global package with sudo or ignore it. |
| `gcloud` not found during `gws auth setup` | Use the manual OAuth flow, or install `gcloud`. |
| “Access blocked” / 403 on login | Add your account as a test user in the OAuth consent screen. |
| Too many scopes / consent error | `gws auth login -s gmail` instead of the `recommended` preset. |
| `redirect_uri_mismatch` | Recreate the OAuth client as **Desktop app** and replace `client_secret.json`. |
| Gmail API not enabled | Enable it in the GCP project; `gws` prints an `enable_url` hint. |
| Exit code 2 | Authentication problem; re-run `gws auth login`. |
| Exit code 3 | Invalid command/arguments; check `gws <service> --help`. |

Gmail OAuth credentials do **not** belong in `~/.secrets.edn`.

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
| Gmail OAuth / Google account | [`gws`](https://github.com/googleworkspace/cli) stores its own credentials in the OS keyring. `map_gmail_ids.clj`, `fetch-bodies.clj`, and `update-label-pipeline.clj` use `gws`, not this file. |
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
