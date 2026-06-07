#!/usr/bin/env bash
# Fail if thrown exception messages in android/data use CJK (comments are OK).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/android/app/src/main/kotlin/app/zerorelay/data"

bad=()
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  if echo "$line" | LC_ALL=C.UTF-8 grep -q '[一-龥]'; then
    bad+=("$line")
  fi
done < <(grep -rnE '(require\(|error\(|throw [A-Za-z]+Exception\()' "$DATA" --include='*.kt' || true)

if ((${#bad[@]})); then
  echo "error: data-layer exception messages must not contain Chinese text:" >&2
  printf '  %s\n' "${bad[@]}" >&2
  echo "Use DataError in data/ and map to UserError in ViewModels." >&2
  exit 1
fi

echo "ok: no CJK text in data-layer exception messages"
