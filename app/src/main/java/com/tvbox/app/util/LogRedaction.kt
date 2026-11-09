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

/**
 * 日志脱敏：处理整段文本（异常 message、第三方报错等）里夹杂的地址。
 *
 * ExoPlayer / OkHttp 的异常文本经常把完整 URL 写进 message，仅对显式的 `url=` 字段脱敏
 * 仍会从 `msg=` 或 `cause=` 漏出路径与 query，因此文本里出现的地址逐处替换为协议 + 主机。
 */
internal fun logSafeText(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return "<空>"
    return URL_IN_TEXT_PATTERN.replace(value) { match ->
        val token = match.value
        // 句末标点/括号不属于地址本身，替换后原样保留。
        val trailing = token.takeLastWhile { it in TRAILING_PUNCTUATION }
        val core = token.dropLast(trailing.length)
        logSafeUrl(core) + trailing
    }
}

// scheme 前缀加上界 `{0,31}`：无界 `*` 在「不含 :// 的长字母数字文本」上会逐起点
// 扩到串尾再回退找 `:`，退化为 O(n²)（100KB 纯文本足以让主线程 ANR）。
// 实际 URL scheme 不超过十余字符，31 的上界不影响任何真实地址。
private val URL_IN_TEXT_PATTERN =
    Regex("""[A-Za-z][A-Za-z0-9+.\-]{0,31}://[^\s"'<>，。；：、）】]+""")

private const val TRAILING_PUNCTUATION = ".,;:!?)]}>"

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
