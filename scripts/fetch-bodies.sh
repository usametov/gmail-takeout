#!/bin/bash
while IFS= read -r id || [ -n "$id" ]; do
    [[ -z "$id" ]] && continue
    echo "=== Fetching: $id ==="
    gws gmail +read --id "$id" --format json
    echo -e "\n"
done < /Users/asel/code/takeout/email_ids.txt