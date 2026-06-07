#!/usr/bin/env bash
# Fail if English and Chinese Android string resources have mismatched keys.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

EN="$ROOT/android/app/src/main/res/values/strings.xml"
ZH="$ROOT/android/app/src/main/res/values-zh-rCN/strings.xml"

for f in "$EN" "$ZH"; do
  if [[ ! -f "$f" ]]; then
    echo "error: missing strings file: $f" >&2
    exit 1
  fi
done

extract_keys() {
  grep -oE '<string name="[^"]+"' "$1" | sed 's/<string name="//;s/"$//' | sort -u
}

en_tmp="$(mktemp)"
zh_tmp="$(mktemp)"
trap 'rm -f "$en_tmp" "$zh_tmp"' EXIT

extract_keys "$EN" >"$en_tmp"
extract_keys "$ZH" >"$zh_tmp"

failed=0
print_keys() {
  while IFS= read -r key; do
    [[ -z "$key" ]] && continue
    printf '  %s\n' "$key"
  done
}

if missing_in_zh="$(comm -23 "$en_tmp" "$zh_tmp")" && [[ -n "$missing_in_zh" ]]; then
  failed=1
  echo "error: keys in values/strings.xml missing from values-zh-rCN/strings.xml:" >&2
  print_keys <<<"$missing_in_zh" >&2
fi
if missing_in_en="$(comm -13 "$en_tmp" "$zh_tmp")" && [[ -n "$missing_in_en" ]]; then
  failed=1
  echo "error: keys in values-zh-rCN/strings.xml missing from values/strings.xml:" >&2
  print_keys <<<"$missing_in_en" >&2
fi

if ((failed)); then
  exit 1
fi

count="$(wc -l <"$en_tmp" | tr -d ' ')"
echo "ok: ${count} string keys match in en and zh-rCN"
