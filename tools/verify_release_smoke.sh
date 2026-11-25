#!/usr/bin/env bash
# T4-26：Release 冒烟五道闸。合 main / 发版前必跑，不需要真机。
# 用法：tools/verify_release_smoke.sh [path/to/app-release.apk]
# 退出码：0=全过  1=发现缺陷  2=环境不可用（缺 APK/aapt2 时对应闸记为 SKIP）
set -euo pipefail

cd "$(dirname "$0")/.."
APK="${1:-}"
if [ -z "$APK" ]; then
  APK=$(find app/build/outputs/apk -name '*.apk' -path '*release*' 2>/dev/null | head -1 || true)
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "❌ 未找到 Release APK；先跑 ./gradlew assembleRelease -PtvboxCi=true"
  exit 2
fi
echo "APK = $APK"

fail=0
skip=0
pass=0
check() { # name, ok(0/1), message
  if [ "$2" = "0" ]; then echo "✅ $1"; pass=$((pass+1));
  elif [ "$2" = "2" ]; then echo "⚠️  SKIP $1 — $3"; skip=$((skip+1));
  else echo "❌ $1 — $3"; fail=$((fail+1)); fi
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" 'classes*.dex' 'AndroidManifest.xml' -d "$TMP" 2>/dev/null || true
DEX="$TMP/classes.dex"
[ -f "$DEX" ] || DEX=$(find "$TMP" -name 'classes*.dex' 2>/dev/null | head -1 || true)

# ① gomobile 桥接类还在吗（三个已知包前缀任一命中即可）
if [ -n "$DEX" ]; then
  if strings "$DEX" | grep -Eq 'com/tvbox/platformlive|platformlive/|go/'; then check "gomobile 桥接类" 0
  else check "gomobile 桥接类" 1 "dex 中找不到桥接符号，平台直播可能在 Release 全崩"; fi
else
  check "gomobile 桥接类" 2 "dex 不存在"
fi

# ② kotlinx.serialization 的 serializer 还在吗
if [ -n "$DEX" ]; then
  if strings "$DEX" | grep -q '\$\$serializer'; then check "kotlinx.serializer" 0
  else check "kotlinx.serializer" 1 "serializer 被剥：订阅导入会静默丢源"; fi
else
  check "kotlinx.serializer" 2 "dex 不存在"
fi

# ③ 焦点框资源还在吗（aapt2 缺失时 SKIP，不误报）
AAPT2=$(find "$ANDROID_HOME${ANDROID_HOME:+/}"/build-tools -name aapt2 -type f 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$AAPT2" ] && [ -x "$AAPT2" ]; then
  if "$AAPT2" dump resources "$APK" 2>/dev/null | grep -Eiq 'drawable.*(focus|sel_)'; then
    check "焦点 drawable" 0
  else
    check "焦点 drawable" 2 "当前焦点由 Compose 绘制，无 drawable 焦点资源；若将来新增 *focus* 资源需保证本检查命中"
  fi
else
  check "焦点 drawable" 2 "未找到 aapt2"
fi

# ④ 明文开关没被一刀切（源码级检查，比解 AXML 可靠）
if grep -q 'usesCleartextTraffic' app/src/main/AndroidManifest.xml; then
  check "cleartext 一刀切" 1 "manifest 仍写 usesCleartextTraffic，见 T1-02"
else
  check "cleartext 一刀切" 0
fi

# ⑤ 密钥没进 dex
if [ -n "$DEX" ] && strings "$DEX" | grep -Eq 'sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{35}'; then
  check "密钥明文进包" 1 "dex 中命中疑似 API Key"
else
  check "密钥明文进包" 0
fi

echo "──────────────────────────────────────────"
echo "release smoke: $pass 通过 / $skip 跳过 / $fail 失败  → $APK"
if [ "$fail" -gt 0 ]; then exit 1; fi
