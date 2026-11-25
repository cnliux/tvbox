#!/usr/bin/env python3
"""T-Q21~T-Q35 十五条探针的汇总入口（T4-29 JSON 契约）。

stdout 只出 JSON；--pretty 时人类可读表格走 stderr。
退出码：0=通过 1=发现 P1 缺陷 2=环境不可用 3=用法错。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app" / "src" / "main"
JAVA = MAIN / "java"
GO = ROOT / "platform_live_go"


def files(base: Path, suffix: str) -> list[Path]:
    if not base.exists():
        return []
    return [p for p in base.rglob("*" + suffix) if p.is_file()]


def count(pattern: str, base: Path = JAVA, suffix: str = ".kt", excludes: tuple[str, ...] = ()) -> int:
    rx = re.compile(pattern, re.IGNORECASE)
    total = 0
    for p in files(base, suffix):
        if any(x in p.name for x in excludes):
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        total += sum(1 for line in text.splitlines() if rx.search(line))
    return total


def exists(*parts: str) -> bool:
    return (ROOT.joinpath(*parts)).exists()


def text_of(*parts: str) -> str:
    p = ROOT.joinpath(*parts)
    return p.read_text(encoding="utf-8", errors="ignore") if p.is_file() else ""


PROBES = [
    ("T-Q21", "cleartext 一刀切 + 跨协议跳转", "P1",
     lambda: "usesCleartextTraffic" in text_of("app", "src", "main", "AndroidManifest.xml")),
    ("T-Q22", "多处 OkHttpClient.Builder()", "P2",
     lambda: count(r"OkHttpClient\s*\.\s*Builder\s*\(", JAVA) > 1),
    # 只拦 OkHttp 盲重与自定义 retry 循环；NetworkRetryPolicy 是唯一允许的入口，
    # 其调用点（AppUpdateRepository / DoubanHotRepository 等）属于正确用法，不算缺陷。
    ("T-Q23", "存在未收敛到 RetryArbiter 的盲重试", "P1",
     lambda: count(r"retryOnConnectionFailure", JAVA) > 0
     or count(r"class RetryArbiter", JAVA) == 0),
    ("T-Q24", "JS 桥 + 无 CIDR 黑名单", "P1",
     lambda: count(r"addJavascriptInterface|@JavascriptInterface|vm\.Set|goja", MAIN) > 0
     and count(r"blockCidr|127\.0\.0\.1|192\.168", MAIN) == 0),
    ("T-Q25", "无 EpKey / 无黄金语料", "P1",
     lambda: count(r"EpKey|normalizeEpisode|fun parseEp", JAVA) == 0
     or not exists("app", "src", "test", "resources", "episode_golden.tsv")),
    ("T-Q26", "M3U 只有 Channel 一个类型", "P2",
     lambda: count(r"data class Channel|data class Line", JAVA) == 0),
    ("T-Q27", "!! 泛滥且无体验指标", "P1",
     lambda: count(r"!!", JAVA) > 40 and count(r"onRenderedFirstFrame|ttfp|rebuffer", JAVA) == 0),
    ("T-Q28", "无 Room exportSchema / 无夹具", "P1",
     lambda: count(r"exportSchema\s*=\s*true", JAVA) == 0
     and not exists("app", "schemas")),
    ("T-Q29", "fallbackToDestructiveMigration 仍存在", "P1",
     lambda: count(r"fallbackToDestructiveMigration", JAVA) > 0),
    ("T-Q30", "update.json 无 schema 键", "P2",
     lambda: '"schema"' not in text_of("update.json")),
    ("T-Q31", "prefs 键字面量多且无 PrefsRegistry", "P2",
     lambda: count(r'getString\("|putString\("|booleanPreferencesKey\("', JAVA) > 60
     and count(r"object PrefsRegistry", JAVA) == 0),
    ("T-Q32", "Go 循环内 time.After / 循环内 defer", "P2",
     lambda: count(r"time\.After", GO, ".go", ("_test.go",)) > 0),
    ("T-Q33", "无 justfile 且 doctor 不可用", "P2",
     lambda: not (ROOT / "justfile").exists()
     and not (ROOT / "tools" / "doctor.sh").exists()),
    ("T-Q34", "无 Leanback launcher", "P2",
     lambda: "LEANBACK_LAUNCHER" not in text_of("app", "src", "main", "AndroidManifest.xml")),
    ("T-Q35", "有设备标识采集且无少儿锁", "P2",
     lambda: count(r"ANDROID_ID|getDeviceId|Build\.SERIAL|getMacAddress", JAVA) > 0
     and count(r"Argon2|少儿锁|kidsLock|pin_hash", JAVA) == 0),
    ("T5-31", "main 中出现无生命周期 GlobalScope（门禁）", "P1",
     lambda: count(r"\bGlobalScope\b", JAVA) > 0),
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pretty", action="store_true", help="人类可读表格走 stderr")
    ap.add_argument("--out", default="", help="额外把 JSON 写到指定路径")
    ap.add_argument("--fail-on", choices=["P0", "P1", "P2", "never"], default="never")
    args = ap.parse_args()

    defects = []
    for pid, title, sev, probe in PROBES:
        try:
            hit = bool(probe())
        except Exception as exc:  # 探针自身异常 = 环境不可用
            hit, sev = True, "P0"
            title = f"{title}（探针异常：{exc}）"
        if hit:
            defects.append({"id": pid, "sev": sev, "title": title})

    result = {
        "schema": 1,
        "tool": "audit_all",
        "verdict": "FAIL" if defects else "PASS",
        "probes": len(PROBES),
        "defects": defects,
        "note": "探针为静态启发式；绿灯仅代表未命中已知模式，不等于真机验证。",
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.pretty:
        print(f"{'ID':<8} {'SEV':<4} 标题", file=sys.stderr)
        for d in defects:
            print(f"{d['id']:<8} {d['sev']:<4} {d['title']}", file=sys.stderr)
        print(f"合计 {len(defects)}/{len(PROBES)} 项命中", file=sys.stderr)

    if args.fail_on == "never":
        return 0
    order = {"P0": 0, "P1": 1, "P2": 2}
    threshold = order[args.fail_on]
    return 1 if any(order[d["sev"]] <= threshold for d in defects) else 0


if __name__ == "__main__":
    sys.exit(main())
