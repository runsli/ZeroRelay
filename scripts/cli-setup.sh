#!/usr/bin/env bash
# One-shot CLI setup on a new machine (Node.js + npm deps + optional global link).
# Usage: ./scripts/cli-setup.sh [--no-link]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
LINK_GLOBAL=1
[[ "${1:-}" == "--no-link" ]] && LINK_GLOBAL=0

if ! command -v node >/dev/null 2>&1; then
  echo "[-] 未找到 node。请安装 Node.js 18+：https://nodejs.org/" >&2
  exit 1
fi

NODE_MAJOR="$(node -p "Number(process.versions.node.split('.')[0])")"
if [[ "$NODE_MAJOR" -lt 18 ]]; then
  echo "[-] 需要 Node.js >= 18，当前: $(node -v)" >&2
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "[-] 未找到 npm（通常随 Node 一起安装）" >&2
  exit 1
fi

echo "[*] ZeroRelay CLI 安装（$ROOT）"
echo "[*] Node $(node -v)"

echo "[*] 安装 npm 依赖…"
npm install

chmod +x "$ROOT/zerorelay" "$ROOT/scripts/cli-setup.sh"

echo ""
echo "[+] 依赖已就绪。可用命令："
echo "    $ROOT/zerorelay help"
echo "    npm run cli -- help"

if [[ "$LINK_GLOBAL" -eq 1 ]]; then
  echo ""
  echo "[*] 注册全局命令 zerorelay / zr（npm link）…"
  if npm link 2>/dev/null; then
    echo "[+] 任意目录可运行: zerorelay  或  zr"
  else
    echo "[!] npm link 未成功（可能需要 sudo 或配置 npm 全局目录）。" >&2
    echo "    改用 PATH（加入 ~/.zshrc 或 ~/.bashrc）：" >&2
    echo "    export PATH=\"$ROOT:\$PATH\"" >&2
  fi
else
  echo ""
  echo "[*] 已跳过 npm link。将仓库加入 PATH 即可："
  echo "    export PATH=\"$ROOT:\$PATH\""
fi

echo ""
echo "[*] 下一步示例："
echo "    zerorelay config set server https://relay.example.com"
echo "    zerorelay config test"
echo "    zerorelay              # 交互主菜单"
echo ""
echo "[*] 本地中继（可选）: cd server && npm install && npm start"
