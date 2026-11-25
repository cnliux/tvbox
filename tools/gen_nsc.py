#!/usr/bin/env python3
"""S1-02：内置 HTTPS 域名白名单的单一真源。

背景：`network_security_config.xml` 对内置域名强制 HTTPS，但白名单靠人肉维护——
新增一个内置 API 忘了登记就静默降级成明文。本脚本从一个列表生成：
  - app/src/main/res/xml/network_security_config.xml
  - app/src/main/java/com/tvbox/app/domain/BuiltinEndpoints.kt（供单测/Policy 引用）
CI 用 `--check` 验证两者与列表一致，diff 非空则 fail。

用法：
    python3 tools/gen_nsc.py             # 覆盖生成
    python3 tools/gen_nsc.py --check     # 只校验，不一致退出 1

退出码（tools/_common.py 唯一真源）：0=成功 1=不一致 2=用法错 3=环境错。
--json 时 stdout 只输出契约 JSON；不带 --json 保持原有输出。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from _common import run

# 内置域名（我们主动请求的高敏域，含 OTA / GitHub 代理）。用户自导入的源不在此列，
# 其 http 兼容仍由 base-config 的 cleartextTrafficPermitted=true 承担。
BUILTIN_DOMAINS: tuple[str, ...] = (
    "douban.com",
    "doubanio.com",
    "360kan.com",
    "360.cn",
    "github.com",
    "githubusercontent.com",
    "jsdelivr.net",
    "gh-proxy.com",
    "gh-proxy.org",
    "ghproxy.net",
)

NSC_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
  网络安全配置策略（由 tools/gen_nsc.py 生成，禁止手改）：
  直播源、订阅地址由用户自行导入，其中包含大量仅提供 http 的旧 IPTV/Plugin 地址，
  因此 base-config 必须保留明文通行。作为补偿，这里对应用内置的固定 API 域名强制 HTTPS。
  新增内置域请改 tools/gen_nsc.py 的 BUILTIN_DOMAINS 后重新生成，CI 会校验一致性（S1-02）。
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <!-- 内置影视/搜索接口：强制 HTTPS（由 BUILTIN_DOMAINS 生成） -->
    <domain-config cleartextTrafficPermitted="false">
{domains}    </domain-config>

    <!-- 仅 Debug 信任用户 CA，Release 不信任，避免中间人风险 -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
"""

KT_ENUM = """package com.tvbox.app.domain

/**
 * S1-02：应用主动请求的内置 API 域名（由 tools/gen_nsc.py 生成，禁止手改）。
 * 与 network_security_config.xml 的 domain-config 一一对应；新增内置域必须登记在这里，
 * 否则 [BuiltinEndpointPolicyTest] 会断言 NSC 与枚举不一致。
 */
enum class BuiltinEndpoint(val host: String) {
__ENTRIES__
    ;

    companion object {
        /** 该 host 是否属于内置高敏域（含子域）。 */
        fun isBuiltin(host: String): Boolean {
            val normalized = host.trim().lowercase().trimEnd('.')
            return entries.any { normalized == it.host || normalized.endsWith("." + it.host) }
        }
    }
}
"""


def generated_files(root: Path) -> dict[Path, str]:
    domains = "".join(
        f'        <domain includeSubdomains="true">{d}</domain>\n' for d in BUILTIN_DOMAINS
    )
    entries = "\n".join(
        '    ' + name + '("' + domain + '"),'
        for name, domain in zip(_enum_names(BUILTIN_DOMAINS), BUILTIN_DOMAINS)
    )
    return {
        root / "app/src/main/res/xml/network_security_config.xml": NSC_XML.format(domains=domains),
        root / "app/src/main/java/com/tvbox/app/domain/BuiltinEndpoints.kt": KT_ENUM.replace("__ENTRIES__", entries),
    }


def _enum_names(domains: tuple[str, ...]) -> list[str]:
    names: list[str] = []
    for domain in domains:
        # 去掉连字符等非字母数字（gh-proxy.com → GhProxyCom），保证生成合法 Kotlin 标识符。
        parts = [part for segment in domain.split(".") for part in segment.split("-") if part]
        name = "".join(part[:1].upper() + part[1:] for part in parts)
        if name and name[0].isdigit():
            name = "D" + name
        names.append(name)
    return names


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--root", default=".", help="仓库根目录")
    parser.add_argument("--check", action="store_true", help="只校验生成物是否最新")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    root = Path(args.root)

    errors: list[str] = []
    generated: list[str] = []
    for path, expected in generated_files(root).items():
        if args.check:
            actual = path.read_text(encoding="utf-8") if path.exists() else ""
            if actual != expected:
                msg = f"{path.relative_to(root)} 与 BUILTIN_DOMAINS 不一致，请运行 python3 tools/gen_nsc.py"
                print(f"::error::{msg}")
                errors.append(msg)
            else:
                print(f"{path.relative_to(root)}: 已同步")
                generated.append(path.relative_to(root).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected, encoding="utf-8")
            print(f"已生成 {path.relative_to(root)}")
            generated.append(path.relative_to(root).as_posix())
    return (1 if errors else 0), {"errors": errors, "generated": generated, "check": args.check}


def main(argv: list[str] | None = None) -> int:
    return run("gen_nsc", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    raise SystemExit(main())
