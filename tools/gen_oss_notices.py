#!/usr/bin/env python3
"""S2-08：开源依赖许可证清单生成器（单一真源 + CI diff 门）。

从 gradle/libs.versions.toml 与 platform_live_go/go.mod 读取**实际声明的依赖版本**，
与下方 LICENSES 登记表合并，生成 app/src/main/assets/oss_notices.json。
新增依赖未登记许可证 → `--check` 退出 1（对应「新增依赖不登记 = 构建红」）。

注意：这是人工核对的许可证登记表（不是 SCA 全扫描）；gosum 里的间接依赖同样登记。
CI 里另有 supply-chain.yml 的 trivy/syft 做漏洞与 SBOM 交叉验证。

用法：
    python3 tools/gen_oss_notices.py
    python3 tools/gen_oss_notices.py --check

退出码（tools/_common.py 唯一真源）：0=成功 1=缺登记/不一致 2=用法错 3=环境错。
--json 时 stdout 只输出契约 JSON；不带 --json 保持原有输出。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from _common import run

# group:name → (license, copyright, url)。新增依赖必须在此登记，--check 才会通过。
LICENSES: dict[str, tuple[str, str, str]] = {
    "androidx.activity:activity-compose": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    "androidx.compose:compose-bom": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.compose.foundation:foundation": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.compose.material:material-icons-core": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.compose.material3:material3": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.compose.runtime:runtime": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.compose.ui:ui": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.core:core": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    "androidx.datastore:datastore-preferences": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    "androidx.lifecycle:lifecycle-runtime-compose": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    "androidx.media3:media3-exoplayer": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/media/media3"),
    "androidx.media3:media3-session": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/media/media3"),
    "androidx.media3:media3-exoplayer-hls": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/media/media3"),
    "androidx.media3:media3-ui": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/media/media3"),
    "androidx.window:window": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    "androidx.profileinstaller:profileinstaller": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    "io.github.anilbeesetti:nextlib-media3ext": ("GPL-3.0", "Anil Beesetti and contributors", "https://github.com/anilbeesetti/nextlib"),
    "io.coil-kt:coil-compose": ("Apache-2.0", "Coil contributors", "https://coil-kt.github.io/coil/"),
    "org.jetbrains.kotlinx:kotlinx-coroutines-android": ("Apache-2.0", "JetBrains s.r.o.", "https://github.com/Kotlin/kotlinx.coroutines"),
    "org.jetbrains.kotlinx:kotlinx-serialization-json": ("Apache-2.0", "JetBrains s.r.o.", "https://github.com/Kotlin/kotlinx.serialization"),
    "com.squareup.okhttp3:okhttp": ("Apache-2.0", "Square, Inc.", "https://square.github.io/okhttp/"),
    "com.google.zxing:core": ("Apache-2.0", "ZXing contributors", "https://github.com/zxing/zxing"),
    # 仅测试/构建期依赖（不进 APK，但许可证义务同样登记）
    "junit:junit": ("EPL-1.0", "JUnit contributors", "https://junit.org/junit4/"),
    "org.jetbrains.kotlinx:kotlinx-coroutines-test": ("Apache-2.0", "JetBrains s.r.o.", "https://github.com/Kotlin/kotlinx.coroutines"),
    "androidx.compose.ui:ui-test-junit4": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.compose.ui:ui-test-manifest": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/compose"),
    "androidx.test.ext:junit": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    "androidx.test:runner": ("Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack"),
    # Go 侧（gomobile 打进 AAR，不出现在 Gradle 依赖树里）
    "go:github.com/dop251/goja": ("MIT", "dop251 and contributors", "https://github.com/dop251/goja"),
    "go:github.com/dlclark/regexp2/v2": ("MIT", "Daniel Clark and contributors", "https://github.com/dlclark/regexp2"),
    "go:github.com/go-sourcemap/sourcemap": ("MIT", "go-sourcemap contributors", "https://github.com/go-sourcemap/sourcemap"),
    "go:github.com/google/pprof": ("BSD-3-Clause", "Google Inc.", "https://github.com/google/pprof"),
    "go:golang.org/x/mobile": ("BSD-3-Clause", "The Go Authors", "https://golang.org/x/mobile"),
    "go:golang.org/x/mod": ("BSD-3-Clause", "The Go Authors", "https://golang.org/x/mod"),
    "go:golang.org/x/sync": ("BSD-3-Clause", "The Go Authors", "https://golang.org/x/sync"),
    "go:golang.org/x/text": ("BSD-3-Clause", "The Go Authors", "https://golang.org/x/text"),
    "go:golang.org/x/tools": ("BSD-3-Clause", "The Go Authors", "https://golang.org/x/tools"),
}

# 只用于测试/构建的依赖不进 APK，但仍列出（附录标注 scope=test）。
TEST_ONLY = {
    "junit:junit",
    "org.jetbrains.kotlinx:kotlinx-coroutines-test",
    "androidx.compose.ui:ui-test-junit4",
    "androidx.compose.ui:ui-test-manifest",
    "androidx.test.ext:junit",
    "androidx.test:runner",
}


def parse_toml(path: Path) -> tuple[dict[str, str], dict[str, str]]:
    versions: dict[str, str] = {}
    libs: dict[str, str] = {}
    section = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("[") and line.endswith("]"):
            section = line
            continue
        if section == "[versions]" and "=" in line:
            key, value = line.split("=", 1)
            versions[key.strip()] = value.strip().strip('"')
        elif section == "[libraries]" and "=" in line:
            alias, body = line.split("=", 1)
            group = re.search(r'group\s*=\s*"([^"]+)"', body)
            name = re.search(r'name\s*=\s*"([^"]+)"', body)
            ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
            literal = re.search(r'version\s*=\s*"([^"]+)"', body)
            if group and name:
                version = versions.get(ref.group(1), "") if ref else (literal.group(1) if literal else "BOM")
                libs[f"{group.group(1)}:{name.group(1)}"] = version
    return versions, libs


def parse_go_mod(path: Path) -> dict[str, str]:
    deps: dict[str, str] = {}
    in_require = False
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("require ("):
            in_require = True
            continue
        if in_require and line == ")":
            in_require = False
            continue
        if line.startswith("require "):
            line = line[len("require "):]
        if in_require or line.startswith(("github.com/", "golang.org/", "gopkg.in/")):
            parts = line.split()
            if len(parts) >= 2:
                deps["go:" + parts[0]] = parts[1]
    return deps


def build_notices(root: Path) -> tuple[list[dict[str, str]], list[str]]:
    _, gradle = parse_toml(root / "gradle/libs.versions.toml")
    go = parse_go_mod(root / "platform_live_go/go.mod")
    notices: list[dict[str, str]] = []
    missing: list[str] = []
    for key, version in sorted({**gradle, **go}.items()):
        entry = LICENSES.get(key)
        if entry is None:
            missing.append(key)
            continue
        license_name, copyright, url = entry
        notices.append({
            "name": key,
            "version": version,
            "license": license_name,
            "copyright": copyright,
            "url": url,
            "scope": "test" if key in TEST_ONLY else "runtime",
        })
    return notices, missing


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--root", default=".")
    parser.add_argument("--check", action="store_true")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    root = Path(args.root)

    notices, missing = build_notices(root)
    errors: list[str] = []
    for key in missing:
        msg = f"依赖 {key} 未在 tools/gen_oss_notices.py 的 LICENSES 中登记许可证"
        print(f"::error::{msg}")
        errors.append(msg)
    if missing:
        return 1, {"errors": errors, "notices": len(notices), "missing": missing}

    target = root / "app/src/main/assets/oss_notices.json"
    generated = json.dumps(notices, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        actual = target.read_text(encoding="utf-8") if target.exists() else ""
        if actual != generated:
            msg = f"{target} 与依赖声明不一致，请运行 python3 tools/gen_oss_notices.py"
            print(f"::error::{msg}")
            errors.append(msg)
            return 1, {"errors": errors, "notices": len(notices), "missing": []}
        print(f"{target.name}: 已同步（{len(notices)} 条）")
        return 0, {"errors": [], "notices": len(notices), "missing": [], "target": target.name}
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(generated, encoding="utf-8")
    print(f"已生成 {target}（{len(notices)} 条）")
    return 0, {"errors": [], "notices": len(notices), "missing": [], "target": target.name}


def main(argv: list[str] | None = None) -> int:
    return run("gen_oss_notices", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
