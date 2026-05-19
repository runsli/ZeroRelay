#!/usr/bin/env bash
# 在新 Terminal 窗口启动带传输日志的本地服务器（macOS）
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8787}"

if [[ "$OSTYPE" != darwin* ]]; then
  echo "start:terminal 仅支持 macOS；其他系统请新开终端执行:"
  echo "  cd $DIR && npm run start:log"
  exit 1
fi

osascript <<EOF
tell application "Terminal"
  activate
  do script "cd '$DIR' && echo 'ZeroRelay 传输日志 · http://localhost:$PORT' && TRANSPORT_LOG=1 node server-local.js"
end tell
EOF

echo "已在新 Terminal 窗口启动服务器（端口 $PORT）"
