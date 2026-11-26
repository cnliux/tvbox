"""虎牙/斗鱼「一起看」长跑监控（自愈版）。

为什么需要它：现场排查断流时，签名服务（虎牙 anti-code / 斗鱼 node 签名）会因
单次异常退出而整体停摆，监控随之静默失败——此前出现过"跑 10 分钟、零产出"。
本工具把两件事绑在一起：

  1. **签名服务自愈**：每次取签名前先探活，失败立即重启子进程并重试；
  2. **逐房间独立统计**：每个房间一个线程，互不影响，单房间失败不拖垮整轮。

口径（与 LiveContinuityRelay + FlvSplicer 的算法一致）：
  - 视频帧指纹去重（12s 窗）、音轨严格单调、换段关键帧黑窗、时间轴平移；
  - 每段会话记录时长，用于判断"是否仍在 120s 周期"还是"退化成重连风暴"。

用法：
    python tools/live_longrun_monitor.py --rounds 10 --rooms 10 --seconds 600
    python tools/live_longrun_monitor.py --rounds 2 --rooms 4 --seconds 60 --json   # T4-29 契约
输出：
    .tmp_dbg/longrun/roundN_<site>_<room>.jsonl 与 roundN_summary.json
    （--out 可改产物目录；--json 时 stdout 只出一行契约 JSON，进度/人类可读走 stderr）
退出码（tools/_common.py 唯一真源）：0=PASS 1=FAIL 2=USAGE_ERROR 3=SKIP（环境不可用）。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import signal
import socket
import subprocess
import sys
import threading
import time

from _common import EXIT_BUSINESS, EXIT_ENV, EXIT_OK, EnvError, run as cli_run

try:
    import requests
except ImportError:  # 依赖缺失 = 环境不可用（verdict=SKIP），不在 import 期直接崩。
    requests = None

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 签名服务用仓库内的 Go 解析服务（cmd/platform-live-server），与 APK 内置解析同一条代码路径；
# 旧的 Python 服务已删除，不要在此处重新引入。
GO_MODULE_DIR = os.path.join(REPO_ROOT, "platform_live_go")
SIGN_PORT = 8899
SIGN_URL = "http://127.0.0.1:%d/v1/live/resolve" % SIGN_PORT
SERVICE = os.path.join(
    REPO_ROOT, ".tmp_dbg", "platform-live-server.exe"
    if os.name == "nt" else "platform-live-server")
OUT_DIR = os.path.join(REPO_ROOT, ".tmp_dbg", "longrun")
GO_BIN = os.environ.get("GO_BIN") or "go"

MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"
)
REPLAY_WINDOW_MS = 12000
_restart_lock = threading.Lock()


# ── 签名服务自愈 ────────────────────────────────────────────────


def _port_open(host: str, port: int, timeout: float = 1.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _build_service() -> None:
    """首次运行时编译 Go 解析服务（gomobile AAR 之外的可执行形态）。"""
    if os.path.exists(SERVICE):
        return
    os.makedirs(os.path.dirname(SERVICE), exist_ok=True)
    subprocess.run(
        [GO_BIN, "build", "-o", SERVICE, "./cmd/platform-live-server"],
        cwd=GO_MODULE_DIR, check=True,
    )


def _start_service() -> None:
    with _restart_lock:
        if _port_open("127.0.0.1", SIGN_PORT):
            return
        _build_service()
        log = open(os.path.join(REPO_ROOT, ".tmp_dbg", "sign_service.log"), "ab")
        subprocess.Popen(
            [SERVICE, "-host", "127.0.0.1", "-port", str(SIGN_PORT)],
            cwd=REPO_ROOT,
            stdout=log,
            stderr=log,
            start_new_session=True,
        )
        # 等端口就绪，最多 15s。
        for _ in range(30):
            if _port_open("127.0.0.1", SIGN_PORT):
                return
            time.sleep(0.5)


def _resolve_params(site: str, rid: str) -> dict:
    """Go 服务的解析接口参数名（roomId）；旧 Python 服务用 room。"""
    return {"site": site, "roomId": rid}


def ensure_service() -> bool:
    """确保签名服务可用；不可用则重启。返回是否最终可用。"""
    probe = _resolve_params("huya", "11336726")
    try:
        r = requests.get(SIGN_URL, params=probe, timeout=25)
        if r.status_code == 200 and "streams" in r.json():
            return True
    except Exception:
        pass
    _start_service()
    try:
        r = requests.get(SIGN_URL, params=probe, timeout=25)
        return r.status_code == 200
    except Exception:
        return False


def sign(site: str, rid: str, idx: int = 0, retries: int = 3):
    """取一条新签名线路；服务不可用时自愈重试。

    注意：**不能缓存**。虎牙 anti-code URL 实测一次性（同 URL 第二次 135ms 即 403），
    缓存会让"重新签名"返回已失效地址，把测量结果污染成重连风暴。
    """
    last = None
    for _ in range(retries):
        try:
            r = requests.get(SIGN_URL, params=_resolve_params(site, rid), timeout=45)
            if r.status_code != 200:
                raise RuntimeError("http%d %s" % (r.status_code, r.text[:120]))
            d = r.json()
            if "error" in d:
                raise RuntimeError(d["error"])
            # Go 服务：候选线路在 streams（含 cdn/url/protocol），与 APK 内置解析一致。
            cands = d.get("streams") or []
            if not cands:
                raise RuntimeError("无候选线路")
            c = cands[idx % len(cands)]
            return d.get("headers") or {}, c["url"], d.get("title", ""), len(cands)
        except Exception as error:  # noqa: BLE001 - 需要捕获所有网络/解析异常
            last = error
            ensure_service()
            time.sleep(1.0)
    raise RuntimeError("签名失败: %s" % last)


# ── 房间池 ─────────────────────────────────────────────────────


def huya_rooms(page: int = 1, size: int = 60):
    s = requests.Session()
    s.headers.update({"User-Agent": MOBILE_UA, "Referer": "https://www.huya.com/"})
    p = {"m": "LiveList", "do": "getLiveListByPage", "tagAll": "0",
         "gameId": "2135", "page": str(page)}
    d = s.get("https://www.huya.com/cache.php", params=p, timeout=15).json()
    return [str(x.get("profileRoom"))
            for x in ((d.get("data") or {}).get("datas") or []) if x.get("profileRoom")]


def douyu_rooms(page: int = 1):
    s = requests.Session()
    s.headers.update({"User-Agent": MOBILE_UA, "Referer": "https://www.douyu.com/directory"})
    d = s.get("https://www.douyu.com/gapi/rkc/directory/mixList/2_208/%d" % page, timeout=15).json()
    return [str(x.get("rid"))
            for x in ((d.get("data") or {}).get("rl") or []) if x.get("type") == 1]


# ── 单房间探针 ─────────────────────────────────────────────────


class Stats:
    def __init__(self):
        self.sessions = 0
        self.replay_dropped = 0
        self.ts_back = 0
        self.keygap_dropped = 0
        self.audio_dropped = 0
        self.bytes = 0
        self.session_ms = []
        self.seg_reset = 0
        self.av_offset_max = 0
        self.errs = []
        self.title = ""
        self.sign_fail = 0

    def as_dict(self):
        return dict(sessions=self.sessions, replay_dropped=self.replay_dropped,
                    ts_back=self.ts_back, keygap_dropped=self.keygap_dropped,
                    audio_dropped=self.audio_dropped, bytes=self.bytes,
                    session_ms=self.session_ms, seg_reset=self.seg_reset,
                    av_offset_max=self.av_offset_max, errs=self.errs[:20],
                    title=self.title, sign_fail=self.sign_fail)


def _is_video_cfg(t, body):
    if t != 9 or len(body) < 2:
        return False
    b0 = body[0]
    return (b0 & 0x0F) == 0 if (b0 & 0x80) else ((b0 & 0x0F) == 7 and body[1] == 0)


def _is_audio_cfg(t, body):
    return t == 8 and len(body) >= 2 and (body[0] >> 4) == 10 and body[1] == 0


def _is_key(t, body):
    if t != 9 or not body:
        return False
    b0 = body[0]
    return ((b0 >> 4) & 0x07) == 1 if (b0 & 0x80) else ((b0 >> 4) & 0x0F) == 1


def run_room(site: str, rid: str, seconds: int, logf) -> Stats:
    st = Stats()
    t0 = time.time()
    line_idx = 0
    try:
        headers, url, title, ncand = sign(site, rid)
        st.title = title
    except Exception as error:  # noqa: BLE001
        st.sign_fail += 1
        st.errs.append("sign:" + str(error)[:120])
        return st

    logf.write(json.dumps({"event": "start", "site": site, "room": rid,
                           "title": title, "cands": ncand}, ensure_ascii=False) + "\n")

    fp_window, last_v, last_a = {}, None, None
    offset = 0
    seg = 0

    while time.time() - t0 < seconds:
        seg += 1
        st.sessions += 1
        seg_t0 = time.time()
        seg_start_last_v = last_v
        deadline_hit = False
        try:
            r = requests.get(url, headers=dict(headers, **{"Accept": "*/*"}),
                             stream=True, timeout=(10, 12))
        except Exception as error:  # noqa: BLE001
            st.errs.append("connect:" + type(error).__name__)
            time.sleep(1.0)
            try:
                headers, url, title, _ = sign(site, rid, line_idx)
            except Exception:  # noqa: BLE001
                st.sign_fail += 1
            continue

        if r.status_code != 200:
            st.errs.append("http%d" % r.status_code)
            line_idx += 1
            try:
                headers, url, title, _ = sign(site, rid, line_idx)
            except Exception:  # noqa: BLE001
                st.sign_fail += 1
            time.sleep(0.5)
            continue

        buf, pos, got_header = b"", 0, False
        pending_align, awaiting_key = True, True
        try:
            for chunk in r.iter_content(65536):
                if time.time() - t0 >= seconds:
                    deadline_hit = True
                    break
                st.bytes += len(chunk)
                buf += chunk
                if not got_header:
                    if len(buf) < 13:
                        continue
                    if buf[:3] != b"FLV":
                        st.errs.append("not_flv")
                        break
                    pos = int.from_bytes(buf[5:9], "big") + 4
                    got_header = True
                while pos + 11 <= len(buf):
                    t = buf[pos] & 0x1F
                    size = int.from_bytes(buf[pos + 1:pos + 4], "big")
                    if size <= 0 or size > (8 << 20):
                        st.errs.append("malformed")
                        buf, pos = b"", 0
                        break
                    if pos + 11 + size + 4 > len(buf):
                        break
                    ts = int.from_bytes(buf[pos + 4:pos + 7], "big") | (buf[pos + 7] << 24)
                    body = buf[pos + 11:pos + 11 + size]
                    pos += 11 + size + 4
                    if t not in (8, 9) or not body:
                        continue
                    if _is_video_cfg(t, body) or _is_audio_cfg(t, body):
                        continue
                    if pending_align:
                        pending_align = False
                        if seg > 1 and (seg_start_last_v is None
                                        or abs(ts + offset - seg_start_last_v) > 60000):
                            st.seg_reset += 1
                            offset = (-ts if seg_start_last_v is None
                                      else seg_start_last_v + 33 - ts)
                    out_ts = ts + offset
                    if awaiting_key:
                        if _is_key(t, body):
                            awaiting_key = False
                        else:
                            st.keygap_dropped += 1
                            continue
                    if t == 9:
                        if last_v is not None and out_ts < last_v:
                            st.ts_back += 1
                            continue
                        fp = hash(bytes([t]) + len(body).to_bytes(4, "big") + body[:64])
                        if fp in fp_window:
                            st.replay_dropped += 1
                            continue
                        fp_window[fp] = out_ts
                        last_v = out_ts
                        for key in [k for k, v in fp_window.items()
                                    if out_ts - v > REPLAY_WINDOW_MS]:
                            fp_window.pop(key, None)
                    else:
                        if last_a is not None and out_ts <= last_a:
                            st.audio_dropped += 1
                            continue
                        last_a = out_ts
                        if last_v is not None:
                            st.av_offset_max = max(st.av_offset_max, abs(last_v - last_a))
                if len(buf) > 4_000_000:
                    buf, pos = buf[pos:], 0
        except Exception as error:  # noqa: BLE001
            st.errs.append("stream:" + type(error).__name__)
        finally:
            try:
                r.close()
            except Exception:  # noqa: BLE001
                pass

        ms = int((time.time() - seg_t0) * 1000)
        st.session_ms.append(ms)
        logf.write(json.dumps({"event": "session", "seg": seg, "ms": ms},
                              ensure_ascii=False) + "\n")
        if deadline_hit:
            break
        if st.sign_fail >= 5:
            # 签名连续失败：本轮提前收手，避免空转占满时间预算。
            break
        line_idx += 1
        try:
            headers, url, title, _ = sign(site, rid, line_idx)
        except Exception:  # noqa: BLE001
            st.sign_fail += 1
    return st


# ── 轮次调度 ───────────────────────────────────────────────────


def one_round(index: int, room_count: int, seconds: int, out_dir: str = "") -> dict:
    ensure_service()
    pool_h = huya_rooms()
    pool_d = douyu_rooms()
    random.shuffle(pool_h)
    random.shuffle(pool_d)
    half = max(1, room_count // 2)
    targets = ([("huya", r) for r in pool_h[:half]]
               + [("douyu", r) for r in pool_d[:room_count - half]])
    out_dir = out_dir or OUT_DIR
    print("[round %d] targets=%s" % (index, targets), flush=True)

    results, lock = {}, threading.Lock()
    os.makedirs(out_dir, exist_ok=True)

    def worker(site, rid):
        path = os.path.join(out_dir, "round%d_%s_%s.jsonl" % (index, site, rid))
        # line buffering：否则整轮跑完才落盘，中途完全看不到进度（曾因此误判"零产出"）。
        with open(path, "w", encoding="utf-8", buffering=1) as f:
            st = run_room(site, rid, seconds, f)
        with lock:
            results["%s:%s" % (site, rid)] = st.as_dict()

    threads = [threading.Thread(target=worker, args=t, daemon=True) for t in targets]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    with open(os.path.join(out_dir, "round%d_summary.json" % index), "w",
              encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    return results


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--rounds", type=int, default=10)
    parser.add_argument("--rooms", type=int, default=10)
    parser.add_argument("--seconds", type=int, default=600)
    parser.add_argument("--pretty", action="store_true", help="人类可读摘要走 stderr")
    parser.add_argument("--out", default="", help="产物输出目录（默认 .tmp_dbg/longrun）")


def _build(args: argparse.Namespace) -> tuple[int, dict]:
    if requests is None:
        raise EnvError("缺少 requests 依赖，无法运行长跑监控")
    if not ensure_service():
        # 签名服务不可达且自愈失败 = 环境不可用（verdict=SKIP），不算业务失败。
        return EXIT_ENV, {"skip_reason": "签名服务不可用（自愈失败）"}

    out_dir = args.out or OUT_DIR
    os.makedirs(out_dir, exist_ok=True)
    all_rounds: dict = {}
    defects: list = []
    for i in range(1, args.rounds + 1):
        try:
            all_rounds[i] = one_round(i, args.rooms, args.seconds, out_dir)
        except Exception as error:  # noqa: BLE001
            print("[round %d] FAILED %s" % (i, error), flush=True)
            all_rounds[i] = {"__error__": str(error)}
            defects.append("round %d: %s" % (i, error))
        with open(os.path.join(out_dir, "all_rounds.json"), "w", encoding="utf-8") as f:
            json.dump(all_rounds, f, ensure_ascii=False, indent=1)
        print("[round %d] done" % i, flush=True)

    # 零会话房间视为缺陷：签名/拉流全失败，监控对该房间没有产出任何数据。
    ok_results = [st for r in all_rounds.values() if isinstance(r, dict)
                  for st in r.values() if isinstance(st, dict) and "__error__" not in st]
    for i, results in all_rounds.items():
        if not isinstance(results, dict):
            continue
        for key, st in results.items():
            if isinstance(st, dict) and "__error__" not in st and st.get("sessions", 0) == 0:
                defects.append("round %d %s: 0 sessions (sign_fail=%s)"
                               % (i, key, st.get("sign_fail", 0)))

    metrics = {
        "rounds": args.rounds,
        "rooms_scheduled": sum(1 for r in all_rounds.values() if isinstance(r, dict)
                               for st in r.values() if isinstance(st, dict)),
        "rooms_ok": sum(1 for st in ok_results if st.get("sessions", 0) > 0),
        "total_sessions": sum(st.get("sessions", 0) for st in ok_results),
        "sign_failures": sum(st.get("sign_fail", 0) for st in ok_results),
        "stream_errors": sum(len(st.get("errs", [])) for st in ok_results),
    }
    artifacts = [os.path.join(out_dir, "all_rounds.json")]
    artifacts += [os.path.join(out_dir, "round%d_summary.json" % i)
                  for i in sorted(k for k in all_rounds if isinstance(k, int))
                  if os.path.isfile(os.path.join(out_dir, "round%d_summary.json" % i))]

    code = EXIT_OK if not defects else EXIT_BUSINESS
    if args.pretty:
        print("longrun %s rooms_ok=%d/%d sessions=%d sign_fail=%d stream_errs=%d defects=%d"
              % ("PASS" if code == EXIT_OK else "FAIL", metrics["rooms_ok"],
                 metrics["rooms_scheduled"], metrics["total_sessions"],
                 metrics["sign_failures"], metrics["stream_errors"], len(defects)),
              file=sys.stderr)
        for d in defects:
            print("  - %s" % d, file=sys.stderr)
    return code, {
        "metrics": metrics,
        "defects": defects,
        "artifacts": artifacts,
        "errors": defects,
    }


def main(argv: list[str] | None = None) -> int:
    return cli_run("live_longrun_monitor", argv, _build, configure=_configure, description=__doc__)


if __name__ == "__main__":
    signal.signal(signal.SIGINT, lambda *_: sys.exit(130))
    sys.exit(main())
