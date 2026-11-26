#!/usr/bin/env python3
"""T1-09 超时预算分层门禁：检测未收敛到 Timeouts.kt 的裸超时预算。

背景：Timeouts.kt（app/src/main/java/com/tvbox/app/data/network/Timeouts.kt）定义了
探活/详情/海报/首帧/OTA 五档预算。各调用点必须引用具名常量（Timeouts.XXX 或本地
命名 const），不允许在 timeout(...) 调用里手写数字。本门禁扫描
app/src/main/java 下的 Kotlin/Java 源码，对以下模式逐行告警：

    connectTimeout(15, ...) / readTimeout(30_000L, ...) / callTimeout(10, ...)
    withTimeout(4_000L) / withTimeoutOrNull(30_000L) / timeout(10_000)

命中不在 Timeouts.kt 内 => warn（告警不阻断，便于增量清理）；
加 --strict 时命中即失败（exit 1），供 CI 全清后升级使用。

退出码：0=无命中或仅告警；1=有命中且 --strict；2=用法错误；3=环境错误（目录缺失）。

用法：
    python3 tools/check_timeouts.py            # 告警模式
    python3 tools/check_timeouts.py --strict   # 门禁模式
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 任意前缀的 *Timeout( 后紧跟数字字面量（含下划线与 L 后缀）：
# 覆盖 OkHttp 的 connect/read/write/callTimeout、协程的 withTimeout(OrNull)、java 的 setTimeout。
PATTERN = re.compile(r"(?i)\w*timeout\s*\(\s*\d[\d_]*(?:\s*[lL])?\b")

# 预算真源：该文件内允许出现字面量。
ALLOWED_BASENAMES = {"Timeouts.kt"}


def scan(root: Path) -> list[tuple[Path, int, str]]:
    """返回 [(文件, 行号, 行内容)]；跳过 Timeouts.kt 与纯注释行。"""
    base = root / "app" / "src" / "main" / "java"
    if not base.is_dir():
        # 环境错误（目录缺失）按契约退出码 3。
        print(f"env error: {base} 不存在（请在仓库根目录运行或传 --root）", file=sys.stderr)
        raise SystemExit(3)
    hits: list[tuple[Path, int, str]] = []
    for path in sorted(list(base.rglob("*.kt")) + list(base.rglob("*.java"))):
        if path.name in ALLOWED_BASENAMES:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue
            if PATTERN.search(line):
                hits.append((path, lineno, stripped))
    return hits


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true",
                        help="命中即失败（exit 1）；默认仅告警（exit 0）")
    parser.add_argument("--root", default="", help="仓库根目录（默认：本脚本所在 tools/ 的上级）")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[1]
    hits = scan(root)

    if not hits:
        print("check_timeouts: 未发现裸 timeout(<数字>) 调用点，全部预算已走具名常量。")
        return 0

    for path, lineno, line in hits:
        rel = path.relative_to(root)
        print(f"WARN {rel}:{lineno}: 裸超时预算 {line[:100]}"
              f"  # 应引用 Timeouts.kt 的档位或本地命名常量", file=sys.stderr)
    print(f"check_timeouts: 共 {len(hits)} 处裸超时预算待清理"
          f"{'（--strict：门禁失败）' if args.strict else '（告警模式，不阻断）'}", file=sys.stderr)
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
