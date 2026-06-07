#!/usr/bin/env bash
# Verify ANDROID_KEYSTORE_BASE64 decodes to a non-empty keystore (local check before updating GitHub Secret).
# Usage: scripts/verify-keystore-base64.sh < base64.txt
#    or: scripts/verify-keystore-base64.sh path/to/base64.txt
set -euo pipefail

strip_b64() {
  tr -d '[:space:]' | tr -d '"' | tr -d "'"
}

if [[ "${1:-}" != "" && -f "${1:-}" ]]; then
  B64="$(strip_b64 < "$1")"
else
  B64="$(strip_b64)"
fi

if [[ -z "$B64" ]]; then
  echo "error: empty input" >&2
  exit 1
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

if ! echo "$B64" | openssl base64 -d -A -out "$TMP" 2>/dev/null; then
  echo "error: invalid base64 (openssl decode failed)" >&2
  exit 1
fi

if [[ ! -s "$TMP" ]]; then
  echo "error: decoded file is empty" >&2
  exit 1
fi

# Java keystore magic: 0xFEEDFEED (various store types) — only check non-trivial size
SIZE="$(wc -c < "$TMP" | tr -d ' ')"
if [[ "$SIZE" -lt 100 ]]; then
  echo "error: decoded size too small (${SIZE} bytes)" >&2
  exit 1
fi

echo "ok: decoded keystore (${SIZE} bytes)"
