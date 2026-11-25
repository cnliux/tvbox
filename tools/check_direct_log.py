#!/usr/bin/env python3
"""S1-07：阻止新增直接 Log.* 调用（存量 144 处作为基线冻结，不要求一次性重构）。

Release 保留 Log 供盒子排障是对的，但裸 `Log.w(tag, "${error.message}")` 会把签名 URL
漏进 logcat。统一入口是 `com.tvbox.app.util.TLog`（惰性 + 强制脱敏）。
本门禁采用**基线冻结**：存量允许，新增直接调用 → PR 红。

用法：
    python3 tools/check_direct_log.py --write-baseline   # 更新基线（仅在确认新增合理时）
    python3 tools/check_direct_log.py                    # CI 门禁

退出码（tools/_common.py 唯一真源）：0=无新增裸 Log 1=门禁命中 2=用法错 3=环境错。
--json 时 stdout 只输出契约 JSON；不带 --json 保持原有输出。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from _common import run

LOG_RE = re.compile(r"\b(?:android\.util\.)?Log\.[diewv]\s*\(")
SKIP_FILES = {"TLog.kt"}  # 门面自身必然直接调用 Log
BASELINE = Path(".direct-log-baseline.txt")


def collect(root: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    for path in sorted((root / "app/src/main/java").rglob("*.kt")):
        if path.name in SKIP_FILES:
            continue
        n = len(LOG_RE.findall(path.read_text(encoding="utf-8", errors="ignore")))
        if n:
            counts[path.relative_to(root).as_posix()] = n
    return counts


def _write_baseline(current: dict[str, int]) -> tuple[int, dict]:
    BASELINE.write_text(
        "".join(f"{path}\t{n}\n" for path, n in sorted(current.items())), encoding="utf-8"
    )
    print(f"基线已写入 {BASELINE}（{len(current)} 个文件 / {sum(current.values())} 处）")
    return 0, {
        "baseline_written": True,
        "files": len(current),
        "calls": sum(current.values()),
    }


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--root", default=".")
    parser.add_argument("--write-baseline", action="store_true")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    root = Path(args.root)
    current = collect(root)

    if args.write_baseline:
        return _write_baseline(current)

    if not BASELINE.exists():
        print(f"::warning::缺少 {BASELINE}，本次作为基线自动生成；请提交该文件")
        return _write_baseline(current)

    baseline: dict[str, int] = {}
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        if "\t" in line:
            path, n = line.rsplit("\t", 1)
            baseline[path] = int(n)

    errors: list[str] = []
    warnings: list[str] = []
    for path, n in sorted(current.items()):
        old = baseline.get(path, 0)
        if n > old:
            msg = f"{path} 直接 Log.* 调用从 {old} 增至 {n}，请改用 TLog（惰性 + 脱敏）"
            print(f"::error::{msg}")
            errors.append(msg)
    for path in sorted(set(baseline) - set(current)):
        if baseline[path] > 0:
            msg = f"{path} 的直接 Log.* 调用已全部移除（{baseline[path]} → 0），可更新基线"
            print(f"::warning::{msg}")
            warnings.append(msg)
    if errors:
        print(f"直接日志门禁失败：{len(errors)} 个文件新增了裸 Log 调用", file=sys.stderr)
    else:
        print(f"直接日志门禁通过：存量 {sum(baseline.values())} 处，无新增")
    return (1 if errors else 0), {
        "errors": errors,
        "warnings": warnings,
        "baseline_calls": sum(baseline.values()),
        "current_calls": sum(current.values()),
    }


def main(argv: list[str] | None = None) -> int:
    return run("check_direct_log", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
