#!/usr/bin/env bash
# T7-44 / S7-38：外部 JSON 黄金语料门禁（只读，不修改任何文件）。
#
# 校验三类生产 JSON 的结构性契约。语料按真实生产格式保存（不是统一信封）：
#   - envelope_v1.json           -> source_config 信封（DataEnvelope）
#   - subscription_v1.json       -> 订阅站点/直播列表
#   - update_manifest_v1.json    -> update.json 清单
#   - platform_live_catalog_v1.json -> 平台直播目录（数组）
# 每条校验：JSON 可解析 + 必需字段齐全 + 类型正确 + checksum/summary 非占位。
#
# 用法：bash tools/check_schema.sh            # 人读输出
#       bash tools/check_schema.sh --json     # JSON 输出（供 CI 消费）
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOMAIN="$ROOT/app/src/main/java/com/tvbox/app/domain/DataEnvelope.kt"
GOLDEN="$ROOT/app/src/test/resources/golden"
JSON_OUT=0
[ "${1:-}" = "--json" ] && JSON_OUT=1

PASS=0
FAIL=0
RESULTS=""

record() {
  local name="$1" ok="$2"
  if [ "$ok" = "1" ]; then
    PASS=$((PASS+1))
    RESULTS="$RESULTS{\"ok\":true,\"check\":\"$name\"},"
    [ "$JSON_OUT" = "0" ] && echo "PASS  $name"
  else
    FAIL=$((FAIL+1))
    RESULTS="$RESULTS{\"ok\":false,\"check\":\"$name\"},"
    [ "$JSON_OUT" = "0" ] && echo "FAIL  $name"
  fi
}

MAX_READER="$(grep -oE 'MAX_READER: Int = [0-9]+' "$DOMAIN" 2>/dev/null | grep -oE '[0-9]+' | head -1)"
[ -z "$MAX_READER" ] && MAX_READER=0
record "MAX_READER 已在 DataEnvelope 定义" "$([ "$MAX_READER" -gt 0 ] && echo 1 || echo 0)"

# lint_corpus <file> <kind>：按 kind 分派字段校验
lint_corpus() {
  python3 - "$1" "$2" "$MAX_READER" <<'PY'
import json, sys
path, kind, max_reader = sys.argv[1], sys.argv[2], int(sys.argv[3])
try:
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
except Exception as exc:
    print(f"  JSON 解析失败: {exc}")
    sys.exit(1)

errs = []
def need(obj, field, typ=None):
    if field not in obj:
        errs.append(f"缺字段 {field}")
    elif typ is not None and not isinstance(obj[field], typ):
        errs.append(f"{field} 类型错误（期望 {typ.__name__}）")

if kind == "envelope":
    for f in ("schema", "kind", "minReader", "createdBy"):
        need(data, f)
    need(data, "schema", int)
    need(data, "minReader", int)
    if isinstance(data.get("minReader"), int) and data["minReader"] > max_reader:
        errs.append(f"minReader={data['minReader']} > MAX_READER={max_reader}")
    # 信封有两种合法形态：标准 DataEnvelope(payload=...)，
    # 或历史扁平格式（顶层直接带 sites/format 等 payload 字段）；两者都要能读。
    if "payload" not in data and not any(k in data for k in ("sites", "format")):
        errs.append("既无 payload 也无扁平 payload 字段（sites/format）")
    chk = data.get("checksum")
    if chk is not None and (not isinstance(chk, str) or len(chk) < 16):
        errs.append(f"checksum 占位或非法: {chk!r}")
elif kind == "subscription":
    for f in ("sites", "lives"):
        need(data, f, list)
elif kind == "update_manifest":
    for f in ("versionCode", "versionName", "apkUrl", "apkSha256", "apkSize", "force", "changelog"):
        need(data, f)
    need(data, "versionCode", int)
    need(data, "force", bool)
elif kind == "platform_catalog":
    if not isinstance(data, list) or not data:
        errs.append("平台直播目录应为非空数组")
    else:
        for i, item in enumerate(data):
            if not isinstance(item, dict) or not any(k in item for k in ("name", "siteName", "title", "id")):
                errs.append(f"第 {i} 项缺名称字段")

if errs:
    for e in errs:
        print(f"  {e}")
    sys.exit(1)
sys.exit(0)
PY
}

echo "== 黄金语料 schema 契约 =="
for spec in "envelope_v1.json:envelope" \
            "update_manifest_v1.json:update_manifest" \
            "subscription_v1.json:subscription" \
            "platform_live_catalog_v1.json:platform_catalog"; do
  name="${spec%%:*}"
  kind="${spec##*:}"
  file="$GOLDEN/$name"
  if [ ! -f "$file" ]; then
    record "$name 存在" 0
    continue
  fi
  out="$(lint_corpus "$file" "$kind" 2>&1)"
  if [ -z "$out" ]; then
    record "$name 结构/$([ "$kind" = envelope ] && echo '版本/checksum' || echo '关键字段') 合法" 1
  else
    record "$name 结构合法" 0
    [ "$JSON_OUT" = "0" ] && echo "$out"
  fi
done

if [ "$JSON_OUT" = "1" ]; then
  body="${RESULTS%,}"
  echo "{\"ok\":$([ "$FAIL" -eq 0 ] && echo true || echo false),\"tool\":\"check_schema\",\"pass\":$PASS,\"fail\":$FAIL,\"checks\":[$body]}"
  [ "$FAIL" -eq 0 ] && exit 0 || exit 1
fi

echo "─────────────────────────────"
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
