#!/usr/bin/env python3
"""S1-04：发布前扫描 APK 的 dex/资源里是否混进了明文 API Key。

BuildConfig.AI_API_KEY 是编译期常量，会以明文躺在 classes.dex 的 string pool 中；
任何人 apktool d 后 grep 即可拿到。本脚本作为发布硬闸：命中 sk-/AIza 直接 fail。

用法：
    python3 tools/check_no_secret_in_dex.py --apk app-release.apk
    python3 tools/check_no_secret_in_dex.py --apk dist/a.apk --apk dist/b.apk
    python3 tools/check_no_secret_in_dex.py --self-test

不依赖 apkanalyzer/aapt2：直接读 ZIP 条目并对 bytes 做正则，Windows/CI 都能跑。
退出码（tools/_common.py 唯一真源）：0=无密钥 1=发现疑似密钥/文件异常 2=用法错 3=环境错。
--json 时 stdout 只输出契约 JSON；不带 --json 保持原有输出。
"""
from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path

from _common import UsageError, run

PATTERNS = (
    re.compile(rb"sk-[A-Za-z0-9]{20,}"),
    re.compile(rb"AIza[0-9A-Za-z_\-]{35}"),
)
SCAN_SUFFIXES = (".dex", ".xml", ".json", ".properties", ".arsc", ".txt")


def scan_bytes(blob: bytes, label: str) -> list[str]:
    findings: list[str] = []
    for pattern in PATTERNS:
        for match in pattern.finditer(blob):
            token = match.group(0).decode("ascii", "replace")
            findings.append(f"{label}: {pattern.pattern.decode()} 命中 -> {token[:8]}***")
    return findings


def scan_apk(path: Path) -> list[str]:
    findings: list[str] = []
    if not path.is_file():
        return [f"{path}: 文件不存在"]
    try:
        with zipfile.ZipFile(path) as zf:
            for info in zf.infolist():
                if info.is_dir() or not info.filename.endswith(SCAN_SUFFIXES):
                    continue
                findings += scan_bytes(zf.read(info), f"{path.name}!{info.filename}")
    except (zipfile.BadZipFile, OSError) as error:
        findings.append(f"{path}: 无法读取 APK（{error}）")
    return findings


def self_test() -> int:
    assert scan_bytes(b"ok=abcdefghijklmnopqrstuvwxyz", "fake.dex") == []
    hits = scan_bytes(b"apikey=sk-abcdefghijklmnopqrstuvwxyz1234", "classes.dex")
    assert len(hits) == 1 and "sk-" in hits[0]
    hits = scan_bytes(b"key=AIza" + b"A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5P6Q7R8S9", "strings.xml")
    assert len(hits) == 1 and "AIza" in hits[0]
    print("check_no_secret_in_dex self-test: 3 组断言通过")
    return 0


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--apk", action="append", default=[], help="待扫描 APK（可多次）")
    parser.add_argument("--self-test", action="store_true")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    if args.self_test:
        code = self_test()
        return code, {"self_test": True, "assertions": 3}
    if not args.apk:
        # 用法错误统一由 run() 映射为退出码 2（--json 时输出契约 JSON）。
        raise UsageError("需要 --apk（或使用 --self-test）")

    findings: list[str] = []
    for apk in args.apk:
        findings += scan_apk(Path(apk))
    for finding in findings:
        print(f"::error::{finding}")
    if findings:
        print(f"APK 中发现 {len(findings)} 处疑似明文密钥，禁止发布", file=sys.stderr)
    else:
        print(f"扫描 {len(args.apk)} 个 APK：未发现明文 sk-/AIza 密钥")
    return (1 if findings else 0), {
        "errors": findings,
        "scanned": len(args.apk),
        "findings": len(findings),
    }


def main(argv: list[str] | None = None) -> int:
    return run("check_no_secret_in_dex", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
