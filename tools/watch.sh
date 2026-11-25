#!/usr/bin/env bash
# T8-48：改完 .kt 抬头即见。监听源码变更 → 增量编译 → 安装 → 拉起上次 Activity。
# 需要 adb 已连接；不碰遥控器，电视端尤其舒服。
set -euo pipefail
cd "$(dirname "$0")/.."

PKG="${TVBOX_PKG:-com.tvbox.app}"
ACTIVITY="${TVBOX_ACTIVITY:-$PKG/.MainActivity}"
WATCH_DIRS="app/src/main/java app/src/main/res app/src/main/AndroidManifest.xml"

command -v adb >/dev/null 2>&1 || { echo "❌ adb 不在 PATH"; exit 2; }
if ! command -v fswatch >/dev/null 2>&1 && ! command -v inotifywait >/dev/null 2>&1; then
  echo "❌ 需要 fswatch(macOS/BSD) 或 inotifywait(Linux)；Windows 请用 just ui 手动编译"
  exit 2
fi

build_install_launch() {
  echo "↻ 检测到变更，增量编译安装…"
  ./gradlew :app:compileDebugKotlin installDebug --console=plain || { echo "编译失败，继续监听"; return 0; }
  adb shell am start -S -n "$ACTIVITY" >/dev/null 2>&1 || true
  echo "✅ 已拉起 $ACTIVITY —— 抬头看电视即可"
}

build_install_launch
while true; do
  if command -v fswatch >/dev/null 2>&1; then
    fswatch -1 -r -e '.*' -i '\\.kt$' -i '\\.xml$' $WATCH_DIRS >/dev/null || true
  else
    inotifywait -q -r -e modify,create,delete $WATCH_DIRS >/dev/null || true
  fi
  sleep 1
  build_install_launch
done
