#!/usr/bin/env bash
# Print base64 for ANDROID_KEYSTORE_BASE64 (do not commit output).
# Usage: print-github-keystore-secret.sh [path/to/release.jks] [--raw]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RAW=0
JKS="${1:-$ROOT/android/zerorelay-release.jks}"
[[ "${2:-}" == "--raw" || "${1:-}" == "--raw" ]] && RAW=1
[[ "${1:-}" == "--raw" ]] && JKS="${2:-$ROOT/android/zerorelay-release.jks}"
if [[ ! -f "$JKS" ]]; then
  echo "usage: $0 [path/to/release.jks] [--raw]" >&2
  exit 1
fi
encode() {
  if base64 --help 2>&1 | grep -q GNU; then
    base64 -w0 "$JKS"
  else
    base64 -i "$JKS" | tr -d '\n'
  fi
}
if [[ "$RAW" -eq 1 ]]; then
  encode
  exit 0
fi
echo "Paste into GitHub → Settings → Secrets → ANDROID_KEYSTORE_BASE64:"
echo "(single line, no quotes — verify: scripts/verify-keystore-base64.sh <( $0 --raw $JKS ))"
echo ""
encode
echo ""
