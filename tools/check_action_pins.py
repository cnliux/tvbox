#!/usr/bin/env python3
"""S4-20：校验所有 GitHub Actions 引用都固定到 commit SHA。

用法：
    python3 tools/check_action_pins.py            # 扫描 .github/workflows/*.yml
    python3 tools/check_action_pins.py --root DIR # 指定仓库根

浮动 tag（如 actions/checkout@v4）一旦被移动到恶意 commit，持有 Release 签名私钥的
release.yml 就会被投毒。本脚本把「所有 uses: 必须是 40 位 SHA + 版本注释」变成一条
可本地重跑、可进 CI 的硬门禁。退出码（tools/_common.py 唯一真源）：
0=全部合规 1=发现浮动引用 2=用法错 3=环境错（找不到 workflow）。
--json 时 stdout 只输出契约 JSON；不带 --json 保持原有输出。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from _common import EnvError, run

# uses: <owner>/<repo>[/<path>]@<ref>  后面允许 "# v4" 之类注释
USES_RE = re.compile(r"^\s*uses:\s*([^\s#]+)(?:\s+#.*)?$")
SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")


def scan_workflow(path: Path) -> list[tuple[int, str]]:
    """返回 (行号, 违规的 uses 行内容) 列表。"""
    violations: list[tuple[int, str]] = []
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = USES_RE.match(raw)
        if not match:
            continue
        ref = match.group(1)
        pin = ref.rsplit("@", 1)[-1] if "@" in ref else ""
        if not SHA_RE.match(pin):
            violations.append((lineno, raw.strip()))
    return violations


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    root = Path(args.root)
    workflows = sorted((root / ".github" / "workflows").glob("*.y*ml"))
    if not workflows:
        # 找不到校验对象是环境错误，不是「浮动引用」这一业务失败。
        raise EnvError("未找到 workflow 文件，无法校验")

    total = 0
    errors: list[str] = []
    for workflow in workflows:
        violations = scan_workflow(workflow)
        uses = [line for line in workflow.read_text(encoding="utf-8").splitlines() if USES_RE.match(line)]
        total += len(uses)
        for lineno, text in violations:
            msg = f"{workflow}:{lineno}: action 未固定到 SHA: {text}"
            print(f"::error::{msg}")
            errors.append(msg)
        print(f"{workflow.name}: {len(uses) - len(violations)}/{len(uses)} 已 pin")

    if errors:
        print(f"发现 {len(errors)} 处浮动 action 引用（共 {total} 处）", file=sys.stderr)
    else:
        print(f"全部 {total} 处 action 引用均已固定到 commit SHA")
    return (1 if errors else 0), {"errors": errors, "uses": total, "workflows": len(workflows)}


def main(argv: list[str] | None = None) -> int:
    return run("check_action_pins", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
