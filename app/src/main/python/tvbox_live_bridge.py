"""TVBox APK 内置的平台直播解析桥接层。

Kotlin 侧通过 Chaquopy 调用本模块的 *_json 函数，返回值与旧版 HTTP 服务的
JSON 结构保持一致，因此 APK 不再依赖外部 platform_live_server。
"""

from __future__ import annotations

import json
import threading
from dataclasses import asdict
from pathlib import Path

from server import LiveService

_CACHE_SECONDS = 30
_TIMEOUT_SECONDS = 20
_SERVICE = None
_SERVICE_LOCK = threading.Lock()


def _install_android_signer() -> None:
    """在 APK 内用 Kotlin 的 JS 引擎替代 Node.js 生成抖音 a_bogus 签名。"""
    try:
        from java import jclass
    except ImportError:
        return
    try:
        signer = jclass("com.tvbox.app.data.DouyinSigner")
    except Exception:
        return

    def sign(params_query: str, user_agent: str) -> str:
        return str(signer.sign(params_query, user_agent))

    import douyin_resolver

    douyin_resolver.SIGNER = sign

    import douyu_resolver

    def run_js(script: str, expression: str) -> str:
        result = str(signer.runJs(script, expression))
        if result.startswith("__JS_ERROR__"):
            raise douyu_resolver.DouyuResolverError(result[len("__JS_ERROR__"):])
        return result

    douyu_resolver.RUNNER = run_js


_install_android_signer()


def _service() -> LiveService:
    global _SERVICE
    if _SERVICE is None:
        with _SERVICE_LOCK:
            if _SERVICE is None:
                _SERVICE = LiveService(
                    catalog_path=Path(__file__).with_name("catalog.json"),
                    cache_seconds=_CACHE_SECONDS,
                    request_timeout_seconds=_TIMEOUT_SECONDS,
                )
    return _SERVICE


def _dump(value: object) -> str:
    return json.dumps(value, ensure_ascii=False)


def channels_json() -> str:
    channels = [asdict(channel) for channel in _service().catalog()]
    return _dump({"channels": channels})


def sites_json() -> str:
    return _dump({"sites": [asdict(site) for site in _service().sites()]})


def categories_json(site: str) -> str:
    return _dump(_service().categories(site))


def rooms_json(site: str, category_id: str, page: str) -> str:
    try:
        normalized_page = max(1, int(page))
    except (TypeError, ValueError):
        normalized_page = 1
    return _dump(_service().rooms(site, category_id, normalized_page))


def resolve_json(site: str, room_id: str, refresh: str) -> str:
    force_refresh = str(refresh).strip().lower() in {"1", "true", "yes"}
    return _dump(_service().resolve(site, room_id, force_refresh))
