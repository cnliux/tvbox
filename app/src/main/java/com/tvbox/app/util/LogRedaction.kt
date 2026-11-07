package com.tvbox.app.util

import java.security.MessageDigest

private val SCHEME_HOST_PATTERN =
    Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://(?:[^/?#@]*@)?(\[[0-9a-fA-F:]+\]|[^/?#:@]+)(?::(\d{1,5}))?""")

/**
 * 日志脱敏：只保留协议 + 主机名（含非默认端口），丢弃路径、query 与 fragment。
 *
 * 用户导入的播放源地址不允许落进 logcat，因此所有日志里的地址都必须经过这里。
 * 无法解析出协议与主机的地址退化为纯 hash 标识，既避免泄露又能按同一地址关联日志。
 */
internal fun logSafeUrl(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return "<空>"
    val match = SCHEME_HOST_PATTERN.find(value) ?: return "hash:${shortHash(value)}"
    val scheme = match.groupValues[1].lowercase()
    val host = match.groupValues[2]
    val port = match.groupValues[3].toIntOrNull()
    return buildString {
        append(scheme).append("://").append(host)
        if (port != null && port != defaultPort(scheme)) append(':').append(port)
    }
}

/** 日志里输出请求头时只列名称，值可能含 Referer（完整地址）或 Cookie。 */
internal fun logSafeHeaderNames(headers: Map<String, String>?): String =
    headers?.keys?.sorted()?.joinToString(separator = ",") ?: "无"

private fun defaultPort(scheme: String): Int? = when (scheme) {
    "http", "ws" -> 80
    "https", "wss" -> 443
    "rtmp" -> 1935
    else -> null
}

private fun shortHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.take(4).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}
