#!/usr/bin/env bash
# T8-47：一条命令自检本地环境，并给出「还差几步 / 预计几分钟」。
# 退出码：0=可构建  1=有缺失（stdout 仍给完整清单，CI 可按 1 走 skipped）
set -uo pipefail
cd "$(dirname "$0")/.."

OK="✅"; WARN="⚠️ "; BAD="❌"
missing=0

check_cmd() { # label, command, hint
  local label="$1" cmd="$2" hint="$3"
  if command -v "$cmd" >/dev/null 2>&1; then
    echo "$OK $label  $($cmd --version 2>&1 | head -1)"
  else
    echo "$BAD $label  未找到 $cmd → $hint"
    missing=$((missing+1))
  fi
}

echo "── 聚合视频 本地环境体检 ─────────────────────────────"

# JDK / javac
check_cmd "JDK    " java "安装 JDK 17，并设置 JAVA_HOME=/path/to/jdk17"
if command -v javac >/dev/null 2>&1; then echo "$OK javac  on PATH"; else
  echo "$BAD javac  不在 PATH → export PATH=\$JAVA_HOME/bin:\$PATH"; missing=$((missing+1)); fi

# ANDROID_HOME / SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK=$(sed -n 's/^sdk.dir=//p' local.properties | head -1)
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then echo "$OK ANDROID_HOME  $SDK"
else echo "$BAD ANDROID_HOME  未配置 → export ANDROID_HOME=/path/to/android-sdk（或写 local.properties 的 sdk.dir）"; missing=$((missing+1)); fi

# NDK
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ] && [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
  NDK=$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
fi
if [ -n "$NDK" ] && [ -d "$NDK" ]; then echo "$OK NDK    $NDK"
else echo "$WARN NDK    未找到 → sdkmanager 'ndk;26.3.11579264'（仅 bind 时需要）"; fi

# Go / gomobile
check_cmd "Go     " go "安装 Go（版本见 platform_live_go/go.mod）"
if go env GOPATH >/dev/null 2>&1; then
  GOPATH_BIN="$(go env GOPATH)/bin"
  if command -v gomobile >/dev/null 2>&1 || [ -x "$GOPATH_BIN/gomobile" ]; then
    echo "$OK gomobile  installed"
  else
    echo "$WARN gomobile  not installed → go install golang.org/x/mobile/cmd/gomobile@\$(grep -oP 'v[0-9].*' tools/bind-platform-live.sh | head -1)"
    echo "       并把 $GOPATH_BIN 加入 PATH"
  fi
fi

# AAR / fingerprint
if [ -f app/libs/platformlive.aar ]; then
  echo "$OK AAR     $(du -h app/libs/platformlive.aar | cut -f1)  app/libs/platformlive.aar"
else
  echo "$WARN AAR     缺失 → ./gradlew bindPlatformLive（或 tools/bind-platform-live.sh）≈3min"
fi
if [ -f app/libs/platformlive.fingerprint ]; then echo "$OK AAR 指纹  已存在"; else echo "$WARN AAR 指纹  缺失（首次 bind 会生成）"; fi

# Git hooks（存在即提示，不强制）
if [ -d .git/hooks ] && ls .git/hooks/*.sample >/dev/null 2>&1; then
  echo "$WARN Git hooks  仍是样例；可选：配置 pre-commit 跑 just lint"
else
  echo "$OK Git hooks  已配置或无需配置"
fi

# Gradle wrapper
if [ -f gradlew ] && [ -f gradle/wrapper/gradle-wrapper.jar ]; then echo "$OK Gradle wrapper  ready"
else echo "$BAD Gradle wrapper  缺失 → 从仓库恢复 gradle/wrapper/"; missing=$((missing+1)); fi

echo "──────────────────────────────────────────"
if [ "$missing" -eq 0 ]; then
  echo "结论：环境齐全，可离线 assembleDebug。下一步：just test && just smoke"
  exit 0
else
  echo "结论：还差 $missing 步就能编，预计 $((missing*2)) 分钟。补齐后重跑 ./tools/doctor.sh"
  exit 1
fi
