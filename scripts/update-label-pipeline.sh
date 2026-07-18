#!/usr/bin/env bash
# Full pipeline: update Gmail IDs → fetch bodies → store bodies
#
# Usage: ./scripts/update-label-pipeline.sh <label> [--no-dry-run]
#
# Example:
#   ./scripts/update-label-pipeline.sh "video/youtube"
#   ./scripts/update-label-pipeline.sh "video/youtube" --no-dry-run
#
# Steps:
#   1. Query emails by label, save Message-IDs
#   2. Map Message-IDs → Gmail internal IDs via gws API
#   3. Update database with Gmail IDs (:email/gmail-id, :email/thread-id)
#   4. Fetch email bodies for records with empty body via gws API
#   5. Update database with email bodies (:email/body-truncated)

set -euo pipefail

LABEL="${1:-}"
NO_DRY="${2:-}"

if [ -z "$LABEL" ]; then
  echo "Usage: $0 <label> [--no-dry-run]"
  echo "  label          Gmail label, e.g. 'video/youtube'"
  echo "  --no-dry-run   Actually call gws API and transact to DB"
  exit 1
fi

DB="$HOME/Documents/Takeout/sent-emails-latest.db"
TAKEOUT="$HOME/code/takeout/takeout"
SCRIPTS="$HOME/code/takeout/scripts"
OUTDIR="$HOME/Documents/Takeout"

# Sanitize label for filenames
LABEL_SAFE=$(echo "$LABEL" | tr '/' '-')
MAP_FILE="$OUTDIR/${LABEL_SAFE}-map-ids.edn"
BODIES_FILE="$OUTDIR/${LABEL_SAFE}-bodies.edn"

echo "=== Label: $LABEL ==="
echo "  DB:       $DB"
echo "  Map file: $MAP_FILE"
echo "  Bodies:   $BODIES_FILE"
echo

# ─── Step 1: Map Message-IDs → Gmail internal IDs ────────────────

echo "--- Step 1: Map Message-IDs → Gmail IDs ---"
echo "  Querying emails with label '$LABEL' and piping to map_gmail_ids.clj..."
echo

$TAKEOUT -d "$DB" query -l "$LABEL" -n 0 --format edn \
  | bb "$SCRIPTS/map_gmail_ids.clj" \
      -o "$MAP_FILE" \
      $NO_DRY

[ -f "$MAP_FILE" ] || { echo "ERROR: map file not created"; exit 1; }

echo
echo "  Output: $MAP_FILE"
echo "  Review the file, then run:"
echo "    $TAKEOUT -d $DB update-message-ids --from $MAP_FILE --dry-run"

# ─── Step 2: Update Gmail IDs in database ────────────────────────

if [ "$NO_DRY" = "--no-dry-run" ]; then
  echo
  echo "--- Step 2: Update Gmail IDs in database ---"
  $TAKEOUT -d "$DB" update-message-ids --from "$MAP_FILE"
else
  echo
  echo "--- Step 2: Update Gmail IDs (DRY-RUN) ---"
  $TAKEOUT -d "$DB" update-message-ids --from "$MAP_FILE" --dry-run
  echo
  echo "  DRY-RUN: review above, then re-run with --no-dry-run to apply."
fi

# ─── Step 3: Fetch email bodies ──────────────────────────────────

echo
echo "--- Step 3: Fetch email bodies ---"
echo "  Querying emails with label '$LABEL' and piping to fetch-bodies.clj..."
echo

$TAKEOUT -d "$DB" query -l "$LABEL" -n 0 --format edn \
  | bb "$SCRIPTS/fetch-bodies.clj" \
      -o "$BODIES_FILE" \
      $NO_DRY

[ -f "$BODIES_FILE" ] || { echo "ERROR: bodies file not created"; exit 1; }

echo
echo "  Output: $BODIES_FILE"
echo "  Review the file, then run:"
echo "    $TAKEOUT -d $DB update-bodies --from $BODIES_FILE --dry-run"

# ─── Step 4: Update bodies in database ───────────────────────────

if [ "$NO_DRY" = "--no-dry-run" ]; then
  echo
  echo "--- Step 4: Update bodies in database ---"
  $TAKEOUT -d "$DB" update-bodies --from "$BODIES_FILE"
else
  echo
  echo "--- Step 4: Update bodies (DRY-RUN) ---"
  $TAKEOUT -d "$DB" update-bodies --from "$BODIES_FILE" --dry-run
  echo
  echo "  DRY-RUN: review above, then re-run with --no-dry-run to apply."
fi

echo
echo "=== Pipeline complete for label: $LABEL ==="
