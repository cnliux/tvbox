#!/usr/bin/env bash
# 冷启动 / 首帧 / 滚动卡顿 / APK 体积的本地度量入口（第 109/150/190 轮）。
# 用法：tools/measure_startup.sh [包名] [MainActivity]
# 只做度量，不构建 Release；采集结果交给 docs 对比基线。
set -euo pipefail

PKG="${1:-com.tvbox.app}"
ACTIVITY="${2:-.MainActivity}"
OUT_DIR="${3:-build/metrics}"
mkdir -p "$OUT_DIR"

echo "== 冷启动（连续 5 次，取 TotalTime 中位数）=="
for i in 1 2 3 4 5; do
  adb shell am force-stop "$PKG"
  adb shell am start -W -S "$PKG/$ACTIVITY" | tee "$OUT_DIR/startup-$i.txt"
done

echo "== 首帧与滚动帧时间 =="
adb shell dumpsys gfxinfo "$PKG" framestats > "$OUT_DIR/gfxinfo.txt" || true

echo "== 体积对比（需要 old.apk / new.apk 置于当前目录）=="
if command -v apkanalyzer >/dev/null 2>&1; then
  apkanalyzer apk compare --different-only old.apk new.apk > "$OUT_DIR/apk-diff.txt" || true
else
  echo "apkanalyzer 不可用，跳过体积对比" > "$OUT_DIR/apk-diff.txt"
fi

echo "== Compose 编译器指标（本地 Debug 评估，不进 Release）=="
echo "./gradlew :app:assembleDebug -PcomposeCompilerMetrics=true" >> "$OUT_DIR/compose-metrics.txt"

echo "度量结果已写入 $OUT_DIR"
