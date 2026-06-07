#!/usr/bin/env bash
# Verify local .jks opens with the same credentials you put in GitHub Actions secrets.
# Usage:
#   export ANDROID_KEYSTORE_PASSWORD='...'
#   export ANDROID_KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}"
#   export ANDROID_KEY_ALIAS='zerorelay'
#   ./scripts/verify-release-keystore.sh [path/to/zerorelay-release.jks]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JKS="${1:-$ROOT/android/zerorelay-release.jks}"
ALIAS="${ANDROID_KEY_ALIAS:-zerorelay}"

if [[ ! -f "$JKS" ]]; then
  echo "error: keystore not found: $JKS" >&2
  exit 1
fi
if [[ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ]]; then
  echo "error: set ANDROID_KEYSTORE_PASSWORD (and optionally ANDROID_KEY_PASSWORD, ANDROID_KEY_ALIAS)" >&2
  exit 1
fi

KEY_PASS="${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  if [[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/keytool" ]]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
  fi
fi
KEYTOOL="${KEYTOOL:-keytool}"

if ! "$KEYTOOL" -list \
  -keystore "$JKS" \
  -storepass "$ANDROID_KEYSTORE_PASSWORD" \
  -alias "$ALIAS" \
  -keypass "$KEY_PASS" >/dev/null 2>&1; then
  echo "error: keystore password or alias incorrect for $JKS" >&2
  echo "  alias tried: $ALIAS" >&2
  echo "  fix GitHub secrets ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_PASSWORD / ANDROID_KEY_ALIAS" >&2
  exit 1
fi

echo "ok: keystore opens with alias $ALIAS"
