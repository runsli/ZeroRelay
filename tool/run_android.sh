#!/usr/bin/env bash
# 构建并在 Android 设备/模拟器上运行 ZeroRelay（Jetpack Compose）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"

if ! nc -z localhost 8787 2>/dev/null; then
  echo "提示：本地 server 未在 8787 端口监听。另开终端运行: cd server && npm start"
fi

./gradlew installDebug
adb shell am start -n app.zerorelay/.MainActivity 2>/dev/null || \
  echo "已安装 APK；若无默认设备，请在 Android Studio 选择模拟器后重试。"
