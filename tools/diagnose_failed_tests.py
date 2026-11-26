#!/usr/bin/env python3
"""CI 单测失败诊断：把失败测试的类名+方法+断言摘要用 ::error:: annotation 输出。

背景：Linux runner 上 Unit tests 持续失败，但 Windows 本地全绿（平台/时序相关 flaky），
无法下载 artifact（需 admin 权限）定位具体哪条 @Test 挂了。本脚本解析 JUnit XML 报告，
把失败测试作为 GitHub Actions annotation 打印，这样会出现在 check-run annotations 里，
API 无需登录即可读取，从而在无法下载 artifact 时定位根因。

用法：python3 tools/diagnose_failed_tests.py --dir app/build/test-results/testDebugUnitTest
退出码：恒为 0（诊断是辅助步骤，不因诊断本身让 CI 再失败一次）。
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def emit_annotation(case: str, message: str, suite: str = "") -> None:
    # GitHub annotation 消息不能含换行（会被截断），压成单行。
    flat = " ".join(message.split())[:300]
    # 注意：不要用 `file=自定义标签`——GitHub 只认仓库内真实相对路径，
    # 非真实路径的 annotation 会被静默丢弃（曾导致 CI-DIAGNOSE 全部消失）。
    # 不带 file 的 `::error::` 纯消息一定会计入 check-run annotations。
    who = f"{suite}#{case}" if suite else case
    print(f"::error::CI-Diagnose {who} :: {flat}")


def diagnose(xml_path: str) -> int:
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as e:
        print(f"::error file=CI-DIAGNOSE::无法解析报告 {xml_path}: {e}")
        return 0
    suite = root.get("name") or os.path.basename(xml_path)
    count = 0
    for case in root.iter("testcase"):
        failure = case.find("failure")
        if failure is None:
            continue
        msg = failure.get("message") or ""
        # message 为空时退化为异常类型+首行正文
        if not msg.strip():
            body = (failure.text or "").strip().splitlines()
            msg = body[0] if body else "（无 message，见完整报告）"
        emit_annotation(case.get("name") or "?", msg, suite=suite)
        count += 1
    return count


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="JUnit XML 报告目录")
    args = ap.parse_args()

    reports = sorted(glob.glob(os.path.join(args.dir, "**", "*.xml"), recursive=True))
    if not reports:
        print(f"::error::CI-Diagnose 未在 {args.dir} 找到任何 JUnit XML 报告")
        return 0

    total = 0
    for path in reports:
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                head = fh.read(4096)
        except OSError as e:
            print(f"::error::CI-Diagnose 读取报告失败 {path}: {e}")
            continue
        if "<failure" not in head and "<failure" not in _tail(path):
            continue
        total += diagnose(path)

    if total == 0:
        print("::error::CI-Diagnose 报告存在但无 <failure> 节点，可能是编译/构建失败而非断言失败")
    else:
        print(f"=== 诊断完成：共 {total} 条失败断言（见上方 ::error::）===")
    return 0


def _tail(path: str, nbytes: int = 65536) -> str:
    """失败节点可能出现在大报告尾部，<failure 在头部 4KB 检测不到时补扫尾部。"""
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - nbytes))
            return fh.read()
    except OSError:
        return ""


if __name__ == "__main__":
    sys.exit(main())
