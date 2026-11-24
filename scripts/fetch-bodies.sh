#!/usr/bin/env bash
set -euo pipefail
input="${1:-email_ids.txt}"
while IFS= read -r id || [ -n "$id" ]; do
    [[ -z "$id" ]] && continue
    echo "=== Fetching: $id ==="
    gws gmail +read --id "$id" --format json
    printf '\n'
done < "$input"
