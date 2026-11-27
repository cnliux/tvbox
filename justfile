# 聚合并可执行入口（T8-49）：新人只需记一个词 `just`。
# 所有命令的唯一真源；CI 和本地都调这里，消灭「我本机是好的」。

set shell := ["bash", "-uc"]

default: doctor

# 环境体检：打印还差几步、预计几分钟
doctor:
    ./tools/doctor.sh

# 构建（单包多端，没有 tv/mobile flavor）
build:
    ./gradlew assembleDebug --console=plain

# 只改 Compose UI：别 assemble
ui:
    ./gradlew :app:compileDebugKotlin --console=plain

# 只改 Go：不 bind
build-go:
    cd platform_live_go && go build ./... && go test ./... -count=1

# 重绑 gomobile AAR（我改了 Go 且要真机验证）
aar:
    powershell -ExecutionPolicy Bypass -File tools/bind-platform-live.ps1

# JVM 单测 + Go 单测
test:
    ./gradlew :app:testDebugUnitTest --console=plain
    just test-go

test-go:
    cd platform_live_go && go test ./... -race -count=1

# 五道 Release 冒烟闸（T4-26）
smoke: build
    bash tools/verify_release_smoke.sh

# 15 条探针汇总（实现后启用；未实现时打印待办）
audit *ARGS:
    python tools/audit_all.py {{ARGS}}

# T7-44/S7-38：外部 JSON 黄金语料 schema 门禁（只读）
schema-check:
    bash tools/check_schema.sh

# Go 长跑基线
longrun R="6" N="10" S="600":
    cd platform_live_go && go run ./cmd/platform-live-server -host 127.0.0.1 -port 8868 &
    sleep 3
    python tools/live_longrun_monitor.py --rounds {{R}} --rooms {{N}} --seconds {{S}} --json

# Room/数据层夹具采集（本仓当前无 Room，此目标保留占位）
fixture:
    echo "本仓当前未使用 Room；历史备份走 设置→数据管理→导出。T7-43 待 Room 落地后再启用。"

# 畸形源本地联调
mock:
    python tools/mock_site.py --port 8899 --evil

# 发版前总检查
release-check: doctor smoke audit schema-check
    just longrun 2 2 120

# 发版收尾（T8-52）：自动干 9 步，把必须人工判断的 2 步擦亮了递到面前。
# 第 9 步忘做的代价是「下个版本没法写迁移测试」，故下次运行会因找不到夹具而红。
release-cut V:
    #!/usr/bin/env bash
    set -e
    echo "=== 发版收尾：{{V}} ==="
    ./tools/doctor.sh || { echo "doctor 未全绿，终止发版"; exit 1; }
    ./gradlew :app:testDebugUnitTest -PtvboxSkipPlatformBind=true --console=plain
    just smoke
    just audit
    echo "─────────────────────────────────────────────"
    echo "👇 还剩 2 步，必须你来："
    echo " 9) CI 绿后采集本地库/设置备份夹具，存进 tools/fixtures/ 并 commit"
    echo " 10) 发社区帖：release_highlights 三段 + 身份卡（签名指纹前8字节）+ 已知问题（敢写）"
