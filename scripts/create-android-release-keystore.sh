#!/usr/bin/env bash
# Create android/zerorelay-release.jks for GitHub Release signing (local only, gitignored).
#
# Usage:
#   export ANDROID_KEYSTORE_PASSWORD='your-strong-password'
#   export ANDROID_KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}"
#   ./scripts/create-android-release-keystore.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
JKS="$ANDROID/zerorelay-release.jks"
ALIAS="${ANDROID_KEY_ALIAS:-zerorelay}"

if [[ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ]]; then
  echo "error: set ANDROID_KEYSTORE_PASSWORD (and optionally ANDROID_KEY_PASSWORD, ANDROID_KEY_ALIAS)" >&2
  exit 1
fi

KEY_PASS="${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}"

if [[ -f "$JKS" ]]; then
  echo "error: $JKS already exists — remove it first if you intend to recreate" >&2
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  if [[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/keytool" ]]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
  fi
fi
KEYTOOL="${KEYTOOL:-keytool}"

if ! "$KEYTOOL" -help >/dev/null 2>&1; then
  echo "error: Java/keytool not available. Install JDK 17+ or: export JAVA_HOME=\$(/usr/libexec/java_home)" >&2
  exit 1
fi

"$KEYTOOL" -genkeypair -v \
  -keystore "$JKS" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$ANDROID_KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASS" \
  -dname "CN=ZeroRelay, OU=Dev, O=ZeroRelay, L=Unknown, ST=Unknown, C=US"

chmod 600 "$JKS"

PROPS="$ANDROID/keystore.properties"
if [[ ! -f "$PROPS" ]]; then
  cp "$ANDROID/keystore.properties.example" "$PROPS"
  echo "wrote $PROPS — set storePassword/keyPassword to match your env"
fi

echo ""
echo "Created: $JKS"
echo "  alias: $ALIAS"
echo ""
echo "Next:"
echo "  ./scripts/print-github-keystore-secret.sh android/zerorelay-release.jks --raw > /tmp/ks.b64"
echo "  ./scripts/verify-keystore-base64.sh /tmp/ks.b64"
