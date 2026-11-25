package com.tvbox.app.util

import java.net.InetAddress
import java.net.URI

/**
 * S1-05：弹幕 / DLNA / 分享口令三条外部输入通路的统一契约。
 *
 * 这些输入全部来自不可信对端（平台 STT 帧、SSDP Describe 响应、用户粘贴文本），
 * 必须在进入 Compose / SOAP / 出网请求之前收口，避免超长文本拖垮弱盒、
 * 或用 `file://` / 回环地址把播放器变成 SSRF 探针。
 */
object UntrustedInput {
    const val DEFAULT_MAX_DANMAKU_LENGTH = 80

    /** 弹幕文本：去控制字符、折叠空白、限长，避免一帧超长文本把弱盒卡住。 */
    fun sanitizeDanmaku(raw: String, maxLength: Int = DEFAULT_MAX_DANMAKU_LENGTH): String {
        if (raw.isEmpty()) return ""
        val cleaned = buildString(raw.length.coerceAtMost(256)) {
            var pendingSpace = false
            raw.forEach { ch ->
                when {
                    ch == '\n' || ch == '\r' || ch == '\t' -> pendingSpace = true
                    ch.isISOControl() -> Unit
                    ch.isWhitespace() -> pendingSpace = true
                    else -> {
                        if (pendingSpace && isNotEmpty()) append(' ')
                        pendingSpace = false
                        append(ch)
                    }
                }
            }
        }
        return cleaned.take(maxLength.coerceAtLeast(1))
    }

    /**
     * 校验外部给的播放 URL：只允许 http(s)，并拒绝回环 / 链路本地 / 私网地址
     * （除非显式 `allowPrivateHost`）。返回 null 表示不安全。
     */
    fun requireHttpUrl(
        raw: String,
        allowPrivateHost: Boolean = false,
        allowLoopback: Boolean = false,
    ): String? {
        if (raw.isBlank()) return null
        val uri = runCatching { URI.create(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (!allowLoopback && isLoopback(host)) return null
        if (!allowPrivateHost && isLocalOrLinkLocal(host)) return null
        return raw.trim()
    }

    private fun isLoopback(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return address.isLoopbackAddress
    }

    private fun isLocalOrLinkLocal(host: String): Boolean {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress
    }
}
