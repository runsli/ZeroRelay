#!/usr/bin/env bash
# Resolve VERSION_NAME and VERSION_CODE from a release tag (v1.0.1 or 1.0.1).
# Usage: source scripts/android-version-from-tag.sh v1.0.1
#    or: scripts/android-version-from-tag.sh v1.0.1  (prints export statements)
set -euo pipefail

raw="${1:-}"
if [[ -z "$raw" ]]; then
  echo "usage: android-version-from-tag.sh <v1.0.1>" >&2
  exit 1
fi

tag="${raw#refs/tags/}"
tag="${tag#v}"

if [[ ! "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "error: expected semver tag like v1.0.1, got: $raw" >&2
  exit 1
fi

VERSION_NAME="$tag"
core="${tag%%[-+]*}"
IFS='.' read -r major minor patch <<< "$core"
VERSION_CODE=$(( major * 10000 + minor * 100 + patch ))

export VERSION_NAME
export VERSION_CODE

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  printf 'export VERSION_NAME=%q\n' "$VERSION_NAME"
  printf 'export VERSION_CODE=%q\n' "$VERSION_CODE"
fi
