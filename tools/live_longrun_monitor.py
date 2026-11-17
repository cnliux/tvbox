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
输出：
    .tmp_dbg/longrun/roundN_<site>_<room>.jsonl 与 roundN_summary.json
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

import requests

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER_DIR = os.path.join(REPO_ROOT, "platform_live_server")
SIGN_PORT = 8899
SIGN_URL = "http://127.0.0.1:%d/resolve" % SIGN_PORT
SERVICE = os.path.join(REPO_ROOT, ".tmp_dbg", "renew_server.py")
OUT_DIR = os.path.join(REPO_ROOT, ".tmp_dbg", "longrun")
PY = os.environ.get("BOX_AGENT_PYTHON") or sys.executable

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


def _start_service() -> None:
    with _restart_lock:
        if _port_open("127.0.0.1", SIGN_PORT):
            return
        log = open(os.path.join(REPO_ROOT, ".tmp_dbg", "sign_service.log"), "ab")
        subprocess.Popen(
            [PY, "-u", SERVICE],
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


def ensure_service() -> bool:
    """确保签名服务可用；不可用则重启。返回是否最终可用。"""
    try:
        r = requests.get(SIGN_URL, params={"site": "huya", "room": "11336726"}, timeout=25)
        if r.status_code == 200 and "candidates" in r.json():
            return True
    except Exception:
        pass
    _start_service()
    try:
        r = requests.get(SIGN_URL, params={"site": "huya", "room": "11336726"}, timeout=25)
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
            d = requests.get(SIGN_URL, params={"site": site, "room": rid}, timeout=45).json()
            if "error" in d:
                raise RuntimeError(d["error"])
            cands = d["candidates"]
            c = cands[idx % len(cands)]
            return d["headers"], c["url"], d.get("title", ""), len(cands)
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


def one_round(index: int, room_count: int, seconds: int) -> dict:
    ensure_service()
    pool_h = huya_rooms()
    pool_d = douyu_rooms()
    random.shuffle(pool_h)
    random.shuffle(pool_d)
    half = max(1, room_count // 2)
    targets = ([("huya", r) for r in pool_h[:half]]
               + [("douyu", r) for r in pool_d[:room_count - half]])
    print("[round %d] targets=%s" % (index, targets), flush=True)

    results, lock = {}, threading.Lock()
    os.makedirs(OUT_DIR, exist_ok=True)

    def worker(site, rid):
        path = os.path.join(OUT_DIR, "round%d_%s_%s.jsonl" % (index, site, rid))
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

    with open(os.path.join(OUT_DIR, "round%d_summary.json" % index), "w",
              encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=10)
    ap.add_argument("--rooms", type=int, default=10)
    ap.add_argument("--seconds", type=int, default=600)
    args = ap.parse_args()

    ensure_service()
    all_rounds = {}
    for i in range(1, args.rounds + 1):
        try:
            all_rounds[i] = one_round(i, args.rooms, args.seconds)
        except Exception as error:  # noqa: BLE001
            print("[round %d] FAILED %s" % (i, error), flush=True)
            all_rounds[i] = {"__error__": str(error)}
        with open(os.path.join(OUT_DIR, "all_rounds.json"), "w", encoding="utf-8") as f:
            json.dump(all_rounds, f, ensure_ascii=False, indent=1)
        print("[round %d] done" % i, flush=True)
    return 0


if __name__ == "__main__":
    signal.signal(signal.SIGINT, lambda *_: sys.exit(130))
    sys.exit(main())
