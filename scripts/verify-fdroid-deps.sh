#!/usr/bin/env bash
# Block non-free / F-Droid problematic Android dependencies.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bad=()
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  bad+=("$line")
done < <(
  grep -rEn 'mlkit|play-services|firebase' android \
    --include='*.kt' --include='*.kts' --include='*.toml' 2>/dev/null || true
)

if ((${#bad[@]})); then
  echo "error: dependencies not suitable for F-Droid:" >&2
  printf '  %s\n' "${bad[@]}" >&2
  exit 1
fi

echo "ok: no mlkit/play-services/firebase in android sources"
