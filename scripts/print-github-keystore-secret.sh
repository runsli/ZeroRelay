#!/usr/bin/env bash
# Print base64 for ANDROID_KEYSTORE_BASE64 (do not commit output).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JKS="${1:-$ROOT/android/zerorelay-release.jks}"
if [[ ! -f "$JKS" ]]; then
  echo "usage: $0 [path/to/release.jks]" >&2
  exit 1
fi
echo "Paste into GitHub → Settings → Secrets → ANDROID_KEYSTORE_BASE64:"
echo ""
if base64 --help 2>&1 | grep -q GNU; then
  base64 -w0 "$JKS"
else
  base64 -i "$JKS"
fi
echo ""
