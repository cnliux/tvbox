#!/usr/bin/env bash
# 将 platform_live_go 编译为 Android AAR（gomobile bind），CI(ubuntu) 与本地均可使用。
# 前置：go、Android SDK/NDK（NDK 通过 ANDROID_NDK_HOME 或 $ANDROID_HOME/ndk 探测）。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$REPO_ROOT/platform_live_go"
OUTPUT_AAR="${OUTPUT_AAR:-$REPO_ROOT/app/libs/platformlive.aar}"
NDK_DIR="${ANDROID_NDK_HOME:-}"

if ! command -v go >/dev/null 2>&1; then
    echo "未找到 go，请安装 Go。" >&2
    if [ -x /usr/local/go/bin/go ]; then
        export PATH="$PATH:/usr/local/go/bin"
    else
        exit 1
    fi
fi

if ! command -v gomobile >/dev/null 2>&1; then
    echo "未找到 gomobile，执行：go install golang.org/x/mobile/cmd/gomobile@latest"
    # 需要同时拿 x/mobile 的依赖，绑定库由 gomobile 自动拉取。
    GOPROXY="${GOPROXY:-}" go install golang.org/x/mobile/cmd/gomobile@latest
fi

GOBIN_DIR="$(go env GOPATH)/bin"
if [ -d "$GOBIN_DIR" ]; then
    export PATH="$PATH:$GOBIN_DIR"
fi

if [ -z "$NDK_DIR" ]; then
    if [ -d "$ANDROID_HOME/ndk" ]; then
        NDK_DIR="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -rV | head -n 1)"
    elif [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
        NDK_DIR="$(ls -d "$ANDROID_SDK_ROOT"/ndk/* 2>/dev/null | sort -rV | head -n 1)"
    fi
fi
if [ -z "$NDK_DIR" ]; then
    echo "未找到 Android NDK，请安装 NDK 或设置 ANDROID_NDK_HOME。" >&2
    exit 1
fi
export ANDROID_NDK_HOME="$NDK_DIR"

cd "$MODULE_DIR"
go build ./...
go test ./...

mkdir -p "$(dirname "$OUTPUT_AAR")"
gomobile bind \
    -target=android/arm,android/arm64,android/amd64 \
    -androidapi 24 \
    -o "$OUTPUT_AAR" \
    .

echo "已生成 AAR：$OUTPUT_AAR"
