#!/usr/bin/env bash
# Fail if release signing material is tracked by git (open-source repos must not ship keys).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bad=()
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  case "$path" in
    android/keystore.properties.example) continue ;;
  esac
  bad+=("$path")
done < <(git ls-files '*.jks' '*.keystore' 'android/keystore.properties' 2>/dev/null || true)

if ((${#bad[@]})); then
  echo "error: release signing files must not be committed:" >&2
  printf '  %s\n' "${bad[@]}" >&2
  echo "Keep *.jks and android/keystore.properties local only (see android/keystore.properties.example)." >&2
  exit 1
fi

echo "ok: no signing keys or keystore.properties in git index"
