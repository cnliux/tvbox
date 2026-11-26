#!/usr/bin/env python3
"""T8-52：发版前本地 checklist 自动化（README/CHANGELOG/OSS notice/lockfile/PENDING_ITEMS/schemas/audit）。

在 CI 的 `Pre-release checklist` 步骤中运行，用于在发布前捕获「收尾动作」遗漏：
  ① README 版本行与 tag 一致（README 已改为引用 build.gradle.kts 唯一来源）
  ② CHANGELOG 顶部版本与 tag 一致
  ③ OSS notice（NOTICE.md / OSS_NOTICES.md）存在且非空
  ④ Gradle lockfile（app/gradle.lockfile）存在且非空
  ⑤ PENDING_ITEMS 基线版本存在且不晚于当前 tag（落后仅告警，不阻断）
  ⑥ golden 语料目录（app/src/test/resources/golden/）至少 1 个非空 JSON
  ⑦ 关键发布工具脚本存在（check_schema.sh / release_verify.py / _common.py）

用法：
    python3 tools/release_checklist.py --tag v1.0.45
    python3 tools/release_checklist.py --tag v1.0.45 --json

退出码（tools/_common.py 唯一真源）：0=全部通过 1=至少一项失败
2=用法错 3=环境错。--json 时 stdout 只输出契约 JSON。
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

from _common import BusinessError, UsageError, run

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
TAG_VERSION_RE = re.compile(r"^v?\d+\.\d+\.\d+$")


def _norm(tag: str) -> str:
    return tag[1:] if tag.startswith("v") else tag


def _version_tuple(v: str) -> tuple[int, int, int]:
    parts = v.split(".")
    return (int(parts[0]), int(parts[1]), int(parts[2]))


def _check_file_nonempty(path: Path) -> tuple[bool, str]:
    if not path.exists():
        return False, f"缺少 {path.name}"
    if path.stat().st_size == 0:
        return False, f"{path.name} 为空"
    return True, f"{path.name} 存在且非空"


def _readme_version(root: Path) -> str | None:
    """README 版本行：'当前版本 `x.y.z`' 或 '当前为 `x.y.z`'。"""
    try:
        text = (root / "README.md").read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    m = re.search(r"(?:当前版本|当前为)\s*`v?([0-9]+\.[0-9]+\.[0-9]+)`", text)
    return m.group(1) if m else None


def _changelog_version(root: Path) -> str | None:
    """CHANGELOG 顶部第一个 '## vX.Y.Z' 或 '## X.Y.Z'。"""
    try:
        text = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    m = re.search(r"^##\s+v?([0-9]+\.[0-9]+\.[0-9]+)", text, re.MULTILINE)
    return m.group(1) if m else None


def _pending_baseline(root: Path) -> str | None:
    """PENDING_ITEMS 基线：'最后核对基线：`vX.Y.Z`'。"""
    try:
        text = (root / "PENDING_ITEMS.md").read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    m = re.search(r"最后核对基线[：:]\s*`v?([0-9]+\.[0-9]+\.[0-9]+)`", text)
    return m.group(1) if m else None


def _golden_dir_ok(root: Path) -> tuple[bool, str]:
    golden = root / "app" / "src" / "test" / "resources" / "golden"
    if not golden.is_dir():
        return False, "缺少 golden 语料目录 app/src/test/resources/golden/"
    nonempty = [p for p in golden.glob("*.json") if p.stat().st_size > 0]
    if not nonempty:
        return False, "golden 目录下没有非空 JSON 语料"
    return True, f"golden 语料 {len(nonempty)} 个非空 JSON"


def _check_tools(root: Path) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    for name in ("check_schema.sh", "release_verify.py", "_common.py", "audit_all.py"):
        if not (root / "tools" / name).exists():
            errors.append(f"缺少工具 tools/{name}")
    return errors, warnings


def build(args: argparse.Namespace) -> tuple[int, dict]:
    if not args.tag:
        raise UsageError("必须提供 --tag（如 v1.0.45）")
    if not TAG_VERSION_RE.match(args.tag):
        raise UsageError(f"--tag 格式应为 vX.Y.Z，实际为 {args.tag}")

    root = Path(args.repo).resolve()
    if not (root / "README.md").exists():
        raise BusinessError(f"仓库根目录无 README.md：{root}")

    tag_version = _norm(args.tag)
    problems: list[str] = []
    warnings: list[str] = []
    checks: dict[str, str] = {}

    # ① README 版本
    rv = _readme_version(root)
    if rv is None:
        problems.append("README.md 未找到 '当前版本 `x.y.z`' 版本行")
    elif rv != tag_version:
        problems.append(f"README.md 版本 {rv} 与 tag {tag_version} 不一致")
    else:
        checks["README"] = rv

    # ② CHANGELOG 版本
    cv = _changelog_version(root)
    if cv is None:
        problems.append("CHANGELOG.md 顶部未找到 '## vX.Y.Z' 版本标题")
    elif cv != tag_version:
        problems.append(f"CHANGELOG.md 顶部版本 {cv} 与 tag {tag_version} 不一致")
    else:
        checks["CHANGELOG"] = cv

    # ③ OSS notice
    notice_ok = False
    for name in ("OSS_NOTICES.md", "NOTICE.md"):
        ok, msg = _check_file_nonempty(root / name)
        if ok:
            notice_ok = True
            checks["OSS notice"] = name
            break
    if not notice_ok:
        problems.append("OSS notice（OSS_NOTICES.md / NOTICE.md）缺失或为空")

    # ④ lockfile
    lf = root / "app" / "gradle.lockfile"
    ok, msg = _check_file_nonempty(lf)
    if ok:
        checks["lockfile"] = "app/gradle.lockfile"
    else:
        problems.append(msg)

    # ⑤ PENDING_ITEMS 基线（落后仅告警）
    if not (root / "PENDING_ITEMS.md").exists():
        problems.append("缺少 PENDING_ITEMS.md")
    else:
        pb = _pending_baseline(root)
        if pb is None:
            warnings.append("PENDING_ITEMS.md 未找到 '最后核对基线：`vX.Y.Z`'，无法核对基线")
        elif _version_tuple(pb) < _version_tuple(tag_version):
            warnings.append(f"PENDING_ITEMS 基线 v{pb} 落后于 tag v{tag_version}，建议同步后再发版")
        else:
            checks["PENDING_ITEMS"] = pb

    # ⑥ golden schemas
    ok, msg = _golden_dir_ok(root)
    if ok:
        checks["golden"] = "ok"
    else:
        problems.append(msg)

    # ⑦ 关键工具
    tool_errors, tool_warnings = _check_tools(root)
    problems.extend(tool_errors)
    warnings.extend(tool_warnings)

    if problems:
        print(f"发布 checklist 失败：{len(problems)} 项未通过")
        for p in problems:
            print(f"  ::error:: {p}")
    else:
        print(f"发布 checklist 通过：{args.tag}")
        for k, v in checks.items():
            print(f"  ✓ {k}={v}")
    for w in warnings:
        print(f"  ::warning:: {w}")

    code = 1 if problems else 0
    fields = {
        "tag": args.tag,
        "checks": checks,
        "warnings": warnings,
    }
    return code, fields


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--tag", default="", help="发布 tag，如 v1.0.45")
    parser.add_argument("--repo", default=".", help="仓库根目录，默认当前目录")


def main(argv: list[str] | None = None) -> int:
    return run("release_checklist", argv, build, _configure, description="发版前本地 checklist（T8-52）")


if __name__ == "__main__":
    raise SystemExit(main())
