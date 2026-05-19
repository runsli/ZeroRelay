#!/usr/bin/env bash
# 本地供应链检查（与 CI security-audit.yml 一致）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "== verify no signing material in git =="
bash scripts/verify-no-signing-in-repo.sh
echo "== verify F-Droid-friendly Android deps =="
bash scripts/verify-fdroid-deps.sh
echo "== npm audit (project root) =="
npm audit --audit-level=high
echo "== npm audit (server) =="
(cd server && npm audit --audit-level=high)
echo "ok: no high/critical vulnerabilities"
