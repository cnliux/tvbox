#!/usr/bin/env python3
"""T4-29：tools/ 下 Python 门禁脚本共用的 CLI 契约（唯一真源）。

退出码（所有脚本统一）：
    0 = 成功
    1 = 业务失败（门禁命中、校验不通过、发现缺陷）
    2 = 用法错误（缺参数、未知旗标）
    3 = 环境错误（缺文件/工具、未预期异常）

--json 模式下 stdout 只输出一行 JSON（紧凑、UTF-8 不转义中文）；
人类可读输出（含 ::error:: / ::warning::）一律改道 stderr，便于 CI 分离采集。
JSON 必给字段：schema / ok / tool / exit_code / errors / warnings。

不带 --json 时脚本输出与历史完全一致（向后兼容）。
"""
from __future__ import annotations

import argparse
import contextlib
import json
import sys
from typing import Any, Callable

SCHEMA = 1

EXIT_OK = 0
EXIT_BUSINESS = 1
EXIT_USAGE = 2
EXIT_ENV = 3


class CliError(Exception):
    """带退出码的脚本错误；run() 统一映射为契约 JSON。"""

    def __init__(self, message: str, code: int = EXIT_ENV) -> None:
        super().__init__(message)
        self.message = message
        self.code = code


class UsageError(CliError):
    """用法错误（退出码 2）。"""

    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_USAGE)


class EnvError(CliError):
    """环境错误（退出码 3）。"""

    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_ENV)


class BusinessError(CliError):
    """业务失败（退出码 1）。"""

    def __init__(self, message: str) -> None:
        super().__init__(message, EXIT_BUSINESS)


class ContractParser(argparse.ArgumentParser):
    """用法错误时抛 [UsageError]，不直接 sys.exit，便于 --json 输出契约。"""

    def error(self, message: str) -> None:
        raise UsageError(message)


def add_json_flag(parser: argparse.ArgumentParser) -> None:
    """给脚本 parser 挂上统一的 --json 旗标。"""
    parser.add_argument(
        "--json",
        action="store_true",
        help="stdout 只输出机器可读 JSON（T4-29 契约）",
    )


def wants_json(argv: list[str] | None = None) -> bool:
    """在参数解析失败前也能判断调用方是否要求 JSON。"""
    args = sys.argv[1:] if argv is None else argv
    return "--json" in list(args)


def result(
    tool: str,
    *,
    ok: bool,
    code: int = EXIT_OK,
    errors: list[str] | None = None,
    warnings: list[str] | None = None,
    **fields: Any,
) -> dict[str, Any]:
    """构造带必给字段的契约对象；工具特有字段通过 **fields 附加。"""
    payload: dict[str, Any] = {
        "schema": SCHEMA,
        "ok": bool(ok),
        "tool": tool,
        "exit_code": int(code),
        "errors": list(errors or []),
        "warnings": list(warnings or []),
    }
    payload.update(fields)
    return payload


def emit(payload: dict[str, Any]) -> None:
    """向 stdout 输出唯一一行契约 JSON。"""
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))


def run(
    tool: str,
    argv: list[str] | None,
    build: Callable[[argparse.Namespace], tuple[int, dict[str, Any]]],
    configure: Callable[[argparse.ArgumentParser], None] | None = None,
    description: str = "",
) -> int:
    """统一入口：解析参数 -> 执行 build(args) -> 按契约输出。

    build(args) 返回 (exit_code, extra_fields)。脚本原有的 print 调用保持不变：
    --json 模式下被改道 stderr，stdout 仅留最后的契约 JSON；非 --json 模式下
    行为与改造前一致。
    """
    parser = ContractParser(prog=tool, description=description or f"{tool}（T4-29 CLI 契约）")
    add_json_flag(parser)
    if configure is not None:
        configure(parser)
    json_mode = wants_json(argv)

    try:
        args = parser.parse_args(argv)
    except UsageError as exc:
        if json_mode:
            emit(result(tool, ok=False, code=EXIT_USAGE, errors=[f"用法错误：{exc.message}"]))
        else:
            print(parser.format_usage().strip(), file=sys.stderr)
            print(f"{tool}: error: {exc.message}", file=sys.stderr)
        return EXIT_USAGE

    real_stdout = sys.stdout
    try:
        # 人类可读输出在 --json 模式下改道 stderr；不 redirect 时等价于原行为。
        with contextlib.redirect_stdout(sys.stderr if json_mode else real_stdout):
            code, fields = build(args)
    except UsageError as exc:
        code, fields = EXIT_USAGE, {"errors": [f"用法错误：{exc.message}"]}
    except BusinessError as exc:
        code, fields = exc.code, {"errors": [exc.message]}
    except EnvError as exc:
        code, fields = exc.code, {"errors": [exc.message]}
    except Exception as exc:  # 未预期异常一律视为环境错误，绝不静默吞掉
        code = EXIT_ENV
        fields = {"errors": [f"环境错误：{type(exc).__name__}: {exc}"]}
    finally:
        sys.stdout = real_stdout

    if json_mode:
        payload = result(
            tool,
            ok=(code == EXIT_OK),
            code=code,
            errors=fields.pop("errors", None),
            warnings=fields.pop("warnings", None),
            **fields,
        )
        emit(payload)
    return code
