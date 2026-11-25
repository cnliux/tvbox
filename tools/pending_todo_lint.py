#!/usr/bin/env python3
"""S8-44：把 PENDING_ITEMS.md 与源码 TODO 注释从「看板」变成「会叫醒人的看板」。

PENDING_ITEMS.md 每条可带一行机器可读头（放在条目附近任意行）：
    <!-- id:#254 state:open owner:@cnliux since:1.0.31 verify:grep:PlayerMediaSession.kt -->
    <!-- id:#999 state:wontfix reason:平台不提供该能力，非本项目范围 -->

规则：
  1. state:open 且 verify:grep:<token> 在源码/仓库中已命中 → PR 红，提示把条目移到「已闭环」；
  2. state:open 且 since 距当前 update.json 版本超过 2 个 minor → 输出 ::warning 供周报 issue @owner；
  3. state:wontfix 必须带 reason，否则 PR 红；
  4. 源码中的 TODO 必须是 TODO(域-序号@责任人)，域 ∈ REMOTE/OTA/LIVE/HISTORY/A11Y/PERF/SEC，
     否则 PR 红（无主 TODO 会躺过三个版本）。

用法：
    python3 tools/pending_todo_lint.py --root .
退出码（tools/_common.py 唯一真源）：0=通过（可能含 warning）1=有必须处理的项
2=用法错 3=环境错。--json 时 stdout 只输出契约 JSON，其余输出走 stderr。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from _common import run

HEADER_RE = re.compile(
    r"<!--\s*id:(?P<id>(?:#[0-9]+|S[0-9]+-[0-9]+))\s+state:(?P<state>open|wontfix|done)"
    r"(?P<extra>.*?)-->"
)
KV_RE = re.compile(r"(\w+):([^\s]+)")
TODO_RE = re.compile(r"TODO\(")
TODO_VALID_RE = re.compile(
    r"TODO\((?:REMOTE|OTA|LIVE|HISTORY|A11Y|PERF|SEC)-\d+@[A-Za-z0-9_\-]+\)"
)
VERSION_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
SOURCE_SUFFIXES = {".kt", ".java", ".go", ".xml", ".kts"}


def parse_headers(text: str) -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    for match in HEADER_RE.finditer(text):
        fields = dict(match.groupdict())
        kv = {key: value for key, value in KV_RE.findall(fields.pop("extra", ""))}
        fields.update(kv)
        entries.append(fields)
    return entries


def current_version(root: Path) -> tuple[int, int, int] | None:
    path = root / "update.json"
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8")).get("versionName", "")
    except (ValueError, OSError):
        return None
    match = VERSION_RE.match(str(value).strip())
    return tuple(int(x) for x in match.groups()) if match else None  # type: ignore[return-value]


def minor_distance(current: tuple[int, int, int] | None, since: str) -> int | None:
    match = VERSION_RE.match(since)
    if not match or current is None:
        return None
    old = tuple(int(x) for x in match.groups())
    return (current[0] - old[0]) * 100 + (current[1] - old[1])


def grep_repository(root: Path, token: str) -> bool:
    """在源码文件中查找 token（文件名或文本均算命中），跳过构建产物与 .git。"""
    skip_dirs = {".git", "build", ".gradle", ".kotlin", "dist", "__pycache__"}
    for path in root.rglob("*"):
        if not path.is_file() or any(part in skip_dirs for part in path.parts):
            continue
        if token in path.name:
            return True
        if path.suffix not in SOURCE_SUFFIXES:
            continue
        try:
            if token in path.read_text(encoding="utf-8", errors="ignore"):
                return True
        except OSError:
            continue
    return False


def scan_todos(root: Path) -> list[tuple[Path, int, str]]:
    bad: list[tuple[Path, int, str]] = []
    for sub in ("app/src/main", "platform_live_go"):
        base = root / sub
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix not in SOURCE_SUFFIXES:
                continue
            for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
                if TODO_RE.search(line) and not TODO_VALID_RE.search(line):
                    bad.append((path, lineno, line.strip()))
    return bad


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    root = Path(args.root)

    pending = root / "PENDING_ITEMS.md"
    errors: list[str] = []
    warnings: list[str] = []
    current = current_version(root)
    entries: list[dict[str, str]] = []

    if pending.exists():
        entries = parse_headers(pending.read_text(encoding="utf-8"))
        print(f"PENDING_ITEMS: 解析到 {len(entries)} 条机器可读头，当前版本 {current}")
        for entry in entries:
            ident, state = entry["id"], entry["state"]
            if state == "wontfix" and not entry.get("reason"):
                msg = f"{ident} state:wontfix 缺少 reason（『不做』也是结论，但要写理由）"
                print(f"::error::{msg}")
                errors.append(msg)
                continue
            if state != "open":
                continue
            token = entry.get("verify", "")
            if token.startswith("grep:"):
                needle = token[len("grep:"):]
                if needle and grep_repository(root, needle):
                    msg = (
                        f"{ident} 看起来已实现（main 中已命中 verify:{needle}），"
                        "请把条目移到 PENDING_ITEMS.md 的「已闭环」并标记 state:done"
                    )
                    print(f"::error::{msg}")
                    errors.append(msg)
            distance = minor_distance(current, entry.get("since", ""))
            if distance is not None and distance > 2:
                owner = entry.get("owner", "@维护者")
                msg = f"{ident} 已挂 {distance} 个 minor 版本仍 open，周报 issue 请 @{owner.lstrip('@')} 复核"
                print(f"::warning::{msg}")
                warnings.append(msg)
    else:
        msg = "PENDING_ITEMS.md 不存在，跳过条目校验"
        print(f"::warning::{msg}")
        warnings.append(msg)

    todos = scan_todos(root)
    for path, lineno, line in todos:
        msg = f"{path.relative_to(root)}:{lineno}: TODO 缺少域/序号/责任人: {line}"
        print(f"::error::{msg}")
        errors.append(msg)

    code = 1 if errors else 0
    if code:
        print(f"pending/todo-lint 失败：{len(errors)} 项需要处理", file=sys.stderr)
    else:
        print("pending/todo-lint 通过")
    return code, {
        "errors": errors,
        "warnings": warnings,
        "entries": len(entries),
        "todos": len(todos),
        "version": ".".join(str(x) for x in current) if current else "",
    }


def main(argv: list[str] | None = None) -> int:
    return run("pending_todo_lint", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
