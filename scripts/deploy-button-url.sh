#!/usr/bin/env bash
# 根据 git remote 生成 Cloudflare Deploy 按钮链接（仓库须为 GitHub/GitLab 公开库）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
REMOTE="${1:-origin}"
URL="$(git remote get-url "$REMOTE" 2>/dev/null | sed -E 's#\.git$##; s#git@github.com:#https://github.com/#; s#https://github.com/([^/]+)/([^/]+).*#https://github.com/\1/\2#')"
if [ -z "$URL" ] || [[ "$URL" != https://github.com/* ]]; then
  echo "用法: $0 [remote]" >&2
  echo "需要 GitHub 公开仓库的 origin，例如: https://github.com/you/zero-relay" >&2
  exit 1
fi
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
DEPLOY_URL="https://deploy.workers.cloudflare.com/?url=${URL}/tree/${BRANCH}/server"
echo "Deploy URL (relay Worker):"
echo "$DEPLOY_URL"
echo ""
echo "After deploy: use https://<worker>.workers.dev as the relay URL."
echo "Health: curl https://<worker>.workers.dev/health"
echo ""
echo "Markdown:"
echo "[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](${DEPLOY_URL})"
