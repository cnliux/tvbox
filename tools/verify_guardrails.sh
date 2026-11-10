#!/usr/bin/env bash
# 质量护栏静态核对（第 291–300 轮）。只读，不修改任何文件。
# 用法：bash tools/verify_guardrails.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/tvbox/app"
FAIL=0

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "PASS  $name"
  else
    echo "FAIL  $name"
    FAIL=1
  fi
}

echo "== 遥控器快捷键 =="
check "首页数字键 1~6 仍在" grep -q "KEYCODE_NUMPAD_6" "$SRC/ui/TvBoxApp.kt"
check "播放器左右快退快进仍在" grep -q "onSeekBy" "$SRC/ui/PlayerInput.kt"
check "直播左右切台 / 上下切线路仍在" grep -q "playNextLiveChannel" "$SRC/ui/TvBoxViewModel.kt"

echo "== 设备模式与源导入 =="
check "设备模式枚举仍在" grep -q "enum class DeviceMode" "$SRC/domain/AppSettings.kt"
check "订阅解码入口仍在" test -f "$SRC/data/SubscriptionDecoder.kt"
check "扫码二维码入口仍在" grep -rq "QrCode\|二维码\|扫码" "$SRC/ui"

echo "== 凭据与导出 =="
check "备份敏感键自检仍在" grep -q "hasSensitiveKeys" "$SRC/data/SourceConfigBackup.kt"
check "备份网关阻止含密钥导出" grep -q "backupExportState" "$SRC/data/SourceBackupGateway.kt"

echo "== OTA 清单兼容性 =="
for field in versionCode versionName apkUrl apkSha256 apkSize force changelog; do
  check "update.json 字段 $field" grep -q "\"$field\"" "$ROOT/update.json"
done
check "apkMirrors 为可选新增字段" grep -q "apkMirrors" "$ROOT/app/src/main/java/com/tvbox/app/data/AppUpdateRepository.kt"

echo "== Release 构建约束 =="
check "Release 仅 arm64-v8a" grep -q "arm64-v8a" "$ROOT/app/build.gradle.kts"
check "16KB 页对齐链接参数（sh）" grep -q "max-page-size=16384" "$ROOT/tools/bind-platform-live.sh"
check "16KB 页对齐链接参数（ps1）" grep -q "max-page-size=16384" "$ROOT/tools/bind-platform-live.ps1"

echo "== 结果 =="
if [ "$FAIL" -eq 0 ]; then echo "ALL PASS"; else echo "SOME CHECKS FAILED"; fi
exit "$FAIL"
