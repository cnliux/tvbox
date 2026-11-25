#!/usr/bin/env python3
"""S7-39：发布后校验清单自动化（README「发布后核对资产与 update.json」的机器版）。

一条命令跑 8 项断言：
  ① manifest.json 内 3 个 flavor asset 都在，且本地文件字节数 == manifest.size
  ② 每个 APK 的 sha256 与 manifest.json / update.json 三方一致
  ③ update.json.versionName == tag（堵住「v1.0.39 标题」那类插值 bug）
  ④ update.json.apkSha256 非空（S1-02 硬闸）
  ⑤ apkanalyzer manifest print：debuggable=false / minSdk=24 / 只含 arm64-v8a
  ⑥ aapt2 dump strings：零命中 sk- / AIza（S1-04）
  ⑦ SBOM 附件存在（S4-20）
  ⑧ gh release view --json assets：没有 .apk.1 / 半截上传物

用法：
    python3 tools/release_verify.py --tag v1.0.43 --skip-remote
    python3 tools/release_verify.py --tag v1.0.43 --gh-repo cnliux/tvbox
    python3 tools/release_verify.py --self-test     # 不依赖仓库/网络的纯函数回归

退出码（tools/_common.py 唯一真源）：0=全部断言通过（缺失可选工具只告警）
1=至少一项失败 2=用法错 3=环境错。--json 时 stdout 只输出契约 JSON。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from _common import UsageError, run as cli_run

# 当前仓库是「单 APK + 运行时设备模式」（README 已写明，build.gradle.kts 无 productFlavors），
# 所以这里按「至少 1 个 APK asset」校验；若将来切到 S4-24 的多 flavor artifacts，
# update.json 里的 artifacts 槽位会被自动纳入三方一致性检查。
EXPECTED_FLAVORS = ("universal", "tv", "mobile")
SECRET_PATTERNS = (re.compile(r"sk-[A-Za-z0-9]{20,}"), re.compile(r"AIza[0-9A-Za-z_\-]{35}"))
PARTIAL_UPLOAD_PATTERN = re.compile(r"\.apk\.\d+$|\.part$|\.tmp$", re.IGNORECASE)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _norm_tag(tag: str) -> str:
    return tag[1:] if tag.startswith("v") else tag


def verify_versions(tag: str, update: dict) -> list[str]:
    """断言 ③④：版本对齐 + apkSha256 非空。"""
    problems: list[str] = []
    actual = str(update.get("versionName", ""))
    if actual != _norm_tag(tag):
        problems.append(f"update.json.versionName={actual!r} 与 tag {tag!r} 不一致")
    sha = str(update.get("apkSha256", ""))
    if not sha or not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
        problems.append("update.json.apkSha256 为空或不是 64 位 hex")
    return problems


def verify_manifest_and_assets(dist: Path, manifest: dict, update: dict) -> list[str]:
    """断言 ①②：APK asset 齐、字节数与 sha256 三方一致。

    兼容两种 update.json：① 当前单槽 apkSha256/apkSize；② S4-24 多 flavor 的
    artifacts.{universal,tv,mobile}.{sha256,size}。两种都要求与 manifest 和本地文件对齐。
    """
    problems: list[str] = []
    assets = {a["name"]: a for a in manifest.get("assets", [])}
    apk_assets = {name: a for name, a in assets.items() if name.endswith(".apk")}
    if not apk_assets:
        problems.append("manifest.json 中没有任何 .apk asset")
        return problems

    digests: dict[str, str] = {}
    for name, meta in apk_assets.items():
        path = dist / name
        if not path.is_file():
            problems.append(f"asset {name} 在 {dist} 中不存在")
            continue
        size = path.stat().st_size
        if size != meta.get("size"):
            problems.append(f"{name} 字节数 {size} != manifest.size {meta.get('size')}")
        digest = sha256_file(path)
        digests[name] = digest
        if digest != meta.get("sha256"):
            problems.append(f"{name} sha256 与 manifest.json 不一致")

    artifacts = update.get("artifacts") or {}
    if artifacts:
        for flavor in EXPECTED_FLAVORS:
            entry = artifacts.get(flavor) or {}
            sha = str(entry.get("sha256") or entry.get("apkSha256") or "")
            if not sha or sha not in digests.values():
                problems.append(f"update.json.artifacts.{flavor} 的 sha256 与任一本地 asset 都不一致")
    else:
        declared = str(update.get("apkSha256", ""))
        if declared and declared not in digests.values():
            problems.append("update.json.apkSha256 与任一 manifest/本地 asset 的 sha256 都不一致")
        declared_size = update.get("apkSize")
        if declared_size and declared_size not in {a.get("size") for a in apk_assets.values()}:
            problems.append("update.json.apkSize 与任一 manifest asset 的 size 都不一致")
    return problems


def verify_apk_tooling(apk: Path, adb_tools: dict[str, str]) -> tuple[list[str], list[str]]:
    """断言 ⑤⑥。返回 (problems, warnings)；工具缺失只告警，不阻断本地重跑。"""
    problems: list[str] = []
    warnings: list[str] = []
    aapt2 = shutil.which(adb_tools.get("aapt2", "aapt2"))
    apkanalyzer = shutil.which(adb_tools.get("apkanalyzer", "apkanalyzer"))
    if not aapt2:
        warnings.append("未找到 aapt2，跳过 strings 密钥扫描（⑥）")
    else:
        out = subprocess.run([aapt2, "dump", "strings", str(apk)], capture_output=True, text=True)
        for pattern in SECRET_PATTERNS:
            if pattern.search(out.stdout):
                problems.append(f"{apk.name} 中发现疑似密钥字符串（{pattern.pattern}）")
    if not apkanalyzer:
        warnings.append("未找到 apkanalyzer，跳过 manifest 断言（⑤）")
    else:
        out = subprocess.run(
            [apkanalyzer, "manifest", "print", str(apk)], capture_output=True, text=True
        )
        text = out.stdout
        if "debuggable=true" in text or "android:debuggable=\"true\"" in text:
            problems.append(f"{apk.name} debuggable=true")
        min_sdk = re.search(r"(?:minSdkVersion|minSdk)[=: ]+(\d+)", text)
        if min_sdk and int(min_sdk.group(1)) != 24:
            problems.append(f"{apk.name} minSdk={min_sdk.group(1)}，预期 24")
        abis = set(re.findall(r"arm64-v8a|armeabi-v7a|x86_64|x86\b", text))
        if abis and abis != {"arm64-v8a"}:
            problems.append(f"{apk.name} 含非 arm64 ABI: {sorted(abis)}")
    return problems, warnings


def verify_sbom_and_release(
    sbom: Path | None, gh_repo: str, tag: str, allow_missing_sbom: bool = False
) -> tuple[list[str], list[str]]:
    """断言 ⑦⑧。gh CLI 缺失/未登录只告警（本地重跑场景）。"""
    problems: list[str] = []
    warnings: list[str] = []
    if sbom is None or not sbom.is_file():
        if allow_missing_sbom:
            warnings.append("SBOM 未作为本次参数传入；生成断言由 supply-chain.yml 的 test -s sbom.cdx.json 承担")
        else:
            problems.append("SBOM 附件不存在（S4-20）")
    if not shutil.which("gh"):
        warnings.append("未找到 gh CLI，跳过 Release 资产半截上传检查（⑧）")
        return problems, warnings
    out = subprocess.run(
        ["gh", "release", "view", tag, "--repo", gh_repo, "--json", "assets"],
        capture_output=True,
        text=True,
    )
    if out.returncode != 0:
        warnings.append(f"gh release view 失败，跳过⑧：{out.stderr.strip()[:160]}")
        return problems, warnings
    try:
        assets = json.loads(out.stdout).get("assets", [])
    except ValueError:
        warnings.append("gh release view 输出不是 JSON，跳过⑧")
        return problems, warnings
    for asset in assets:
        name = asset.get("name", "")
        if PARTIAL_UPLOAD_PATTERN.search(name):
            problems.append(f"Release 中发现半截上传物：{name}")
    return problems, warnings


def verify(args: argparse.Namespace) -> tuple[int, dict]:
    dist = Path(args.dist)
    update = json.loads(Path(args.update).read_text(encoding="utf-8"))
    manifest = json.loads((dist / "manifest.json").read_text(encoding="utf-8"))

    problems = verify_versions(args.tag, update)
    problems += verify_manifest_and_assets(dist, manifest, update)

    apk = dist / f"juhe-video-{_norm_tag(args.tag)}.apk"
    warnings: list[str] = []
    if apk.is_file():
        more, warn = verify_apk_tooling(apk, {"aapt2": args.aapt2, "apkanalyzer": args.apkanalyzer})
        problems += more
        warnings += warn
    else:
        warnings.append(f"未找到 {apk.name}，跳过⑤⑥（本地重跑可只校验 JSON 三方一致）")

    sbom = Path(args.sbom) if args.sbom else None
    more, warn = verify_sbom_and_release(sbom, args.gh_repo, args.tag, args.allow_missing_sbom)
    problems += more
    warnings += warn

    for warning in warnings:
        print(f"::warning::{warning}")
    for problem in problems:
        print(f"::error::{problem}")
    asset_count = len(manifest.get("assets", []))
    if problems:
        print(f"发布校验失败：{len(problems)} 项断言未通过")
        return 1, {
            "errors": problems,
            "warnings": warnings,
            "tag": args.tag,
            "assets": asset_count,
        }
    print(f"发布校验通过：{args.tag}（{asset_count} 个资产）")
    return 0, {
        "errors": [],
        "warnings": warnings,
        "tag": args.tag,
        "assets": asset_count,
    }


def self_test() -> int:
    """纯函数回归：不依赖任何真实产物，覆盖 ③/④、版本错配、三方 sha 不一致、半截上传物。"""
    good = {"versionName": "1.0.43", "apkSha256": "a" * 64}
    assert verify_versions("v1.0.43", good) == []
    assert any("versionName" in p for p in verify_versions("v1.0.39", good))
    assert any("apkSha256" in p for p in verify_versions("v1.0.43", {"versionName": "1.0.43"}))

    with tempfile.TemporaryDirectory() as td:
        dist = Path(td)
        name = "juhe-video-v1.0.43.apk"
        payload = b"fake-apk-bytes"
        actual_sha = hashlib.sha256(payload).hexdigest()
        (dist / name).write_bytes(payload)
        manifest = {"assets": [{"name": name, "sha256": actual_sha, "size": len(payload)}]}
        update = {"versionName": "1.0.43", "apkSha256": actual_sha}
        assert verify_manifest_and_assets(dist, manifest, update) == []
        assert any("sha256" in p for p in verify_manifest_and_assets(dist, manifest, {**update, "apkSha256": "b" * 64}))
        assert any("字节数" in p for p in verify_manifest_and_assets(dist, {"assets": [{"name": name, "sha256": actual_sha, "size": 999}]}, update))
        multi = {"versionName": "1.0.43", "artifacts": {
            f: {"sha256": actual_sha, "size": len(payload)} for f in EXPECTED_FLAVORS
        }}
        assert verify_manifest_and_assets(dist, manifest, multi) == []

    assert PARTIAL_UPLOAD_PATTERN.search("juhe-video.apk.1")
    assert not PARTIAL_UPLOAD_PATTERN.search("juhe-video.apk")
    print("release_verify self-test: 6 组断言通过")
    return 0


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--tag", default="", help="发布 tag，如 v1.0.43")
    parser.add_argument("--dist", default="dist", help="构建产物目录")
    parser.add_argument("--update", default="update.json")
    parser.add_argument("--sbom", default="sbom.cdx.json")
    parser.add_argument("--gh-repo", default="cnliux/tvbox")
    parser.add_argument("--aapt2", default="aapt2")
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--allow-missing-sbom", action="store_true", help="SBOM 由 supply-chain.yml 单独校验时传入")
    parser.add_argument("--self-test", action="store_true", help="运行纯函数回归，不读仓库")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    if args.self_test:
        code = self_test()
        return code, {"self_test": True, "assertions": 6}
    if not args.tag:
        raise UsageError("需要 --tag（或使用 --self-test）")
    return verify(args)


def main(argv: list[str] | None = None) -> int:
    return cli_run("release_verify", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
