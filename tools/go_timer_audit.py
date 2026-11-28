#!/usr/bin/env python3
"""Go 循环内 time.After / defer 静态门禁（与 app 侧 GoTimerAudit.scan 同一启发式）。

T5-36 / 问题39：把 JVM 单测里的「已知债」扫描接入 CI 硬门禁。
本脚本只做纯文本扫描：
  - 逐行维护 for 块大括号深度，在 for 块内遇到 `time.After(` 或 `defer ` 即记一条；
  - 与基线 tools/go_timer_audit_baseline.txt 比对，**基线之外的新增违规**使 --check 失败；
  - 基线格式：`<相对 go 路径>:<最大允许条数>`，缺省条数为 1（该文件已知有债务）。

用法：
  python3 tools/go_timer_audit.py --root .            # 打印当前全部违规（供生成基线）
  python3 tools/go_timer_audit.py --root . --check     # CI 门禁：基线外新增违规 → 非 0 退出
  python3 tools/go_timer_audit.py --root . --bootstrap # 用当前违规重写基线
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

FOR_OPEN_RE = re.compile(r"\bfor\b[^@]*\{")
DEFER_RE = re.compile(r"\bdefer\s+")
BASELINE_NAME = "tools/go_timer_audit_baseline.txt"


def scan_source(text: str) -> list[tuple[int, str]]:
    """返回 [(行号, kind)]；kind ∈ {timeafter, defer}，与 GoTimerAudit.scan 逐行等价。"""
    findings: list[tuple[int, str]] = []
    loop_depth = -1
    brace_depth = 0
    for index, raw in enumerate(text.splitlines()):
        line = raw.strip()
        opens_for = bool(FOR_OPEN_RE.search(line))
        before = brace_depth
        brace_depth += line.count("{") - line.count("}")
        if opens_for and loop_depth < 0:
            loop_depth = before + 1
        if loop_depth >= 0 and brace_depth < loop_depth:
            loop_depth = -1
        if loop_depth < 0:
            continue
        if "time.After(" in line:
            findings.append((index + 1, "timeafter"))
        elif DEFER_RE.search(line):
            findings.append((index + 1, "defer"))
    return findings


def iter_go_files(root: Path, include_tests: bool = False):
    """门禁只扫生产 Go 源（AAR / cmd 服务会打进产物）；`*_test.go` 里
    有界循环 + `defer wg.Done()` 是惯用法，启发式会误报，默认排除。"""
    go_dir = root / "platform_live_go"
    if not go_dir.is_dir():
        return
    for path in sorted(go_dir.rglob("*.go")):
        if not include_tests and path.name.endswith("_test.go"):
            continue
        yield path


def load_baseline(root: Path) -> dict[str, int]:
    """基线：相对路径 → 最大允许违规条数。"""
    baseline: dict[str, int] = {}
    base_file = root / BASELINE_NAME
    if not base_file.is_file():
        return baseline
    for raw in base_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        # 允许 `path` 或 `path:count` 两种写法
        rel, sep, count = line.rpartition(":")
        if sep and count.strip().isdigit():
            baseline[rel.strip()] = int(count.strip())
        else:
            baseline[line] = 1
    return baseline


def main() -> int:
    parser = argparse.ArgumentParser(description="Go 循环内 time.After/defer 门禁")
    parser.add_argument("--root", default=".", help="仓库根目录")
    parser.add_argument("--check", action="store_true", help="基线外新增违规 → 退出码 1")
    parser.add_argument("--bootstrap", action="store_true", help="用当前违规重写基线")
    parser.add_argument("--include-tests", action="store_true", help="连 *_test.go 也扫（默认排除，惯用法误报）")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    files = list(iter_go_files(root, include_tests=args.include_tests))
    per_file: dict[str, int] = {}
    detail: list[tuple[str, int, str]] = []
    for path in files:
        findings = scan_source(path.read_text(encoding="utf-8", errors="replace"))
        if findings:
            rel = path.relative_to(root).as_posix()
            per_file[rel] = len(findings)
            detail.extend((rel, ln, kind) for ln, kind in findings)

    if not args.bootstrap:
        for rel, count in sorted(per_file.items()):
            print(f"  {rel}: {count}")
    print(f"扫描 {len(files)} 个 Go 文件，{len(detail)} 处循环内 time.After/defer")

    baseline = load_baseline(root)

    if args.bootstrap:
        base_file = root / BASELINE_NAME
        lines = ["# Go 循环内 time.After/defer 已知债基线（T5-36 / 问题39）",
                 "# 格式：<相对 go 路径>:<最大允许条数>；缺省条数为 1。",
                 "# 某文件债务清零后请删除对应行；新增文件出现违规将直接 fail。",
                 ""]
        for rel, count in sorted(per_file.items()):
            lines.append(f"{rel}:{count}")
        base_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"基线已写入 {BASELINE_NAME}（{len(per_file)} 个文件）")
        return 0

    if args.check:
        violations: list[str] = []
        for rel, count in sorted(per_file.items()):
            allowed = baseline.get(rel)
            if allowed is None:
                violations.append(f"  {rel}: {count} 处（基线未收录 → 新增违规）")
            elif count > allowed:
                violations.append(f"  {rel}: {count} 处（超出基线 {allowed} 处）")
        # 基线里已清零的文件给出提示（不 fail，提醒删基线）
        resolved = [rel for rel in baseline if rel not in per_file and rel.endswith(".go")]
        if violations:
            print("\n[FAIL] goTimerAudit：基线之外的新增违规：")
            for v in violations:
                print(v)
            if resolved:
                print("\n（提示）以下基线条目已无违规，可从基线删除：")
                for rel in resolved:
                    print(f"  {rel}")
            return 1
        if resolved:
            print(f"[WARN] 基线中 {len(resolved)} 个条目已清零，建议删除：")
            for rel in resolved:
                print(f"  {rel}")
        print("[OK] goTimerAudit：无基线外新增违规")
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
