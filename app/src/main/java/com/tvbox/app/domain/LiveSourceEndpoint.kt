package com.tvbox.app.domain

/**
 * IPTV 订阅地址的行内参数解析（对齐 TVBox/IPTV 生态惯例）。
 *
 * 订阅地址可以追加 `|` 分隔的头部参数，防止部分源服务器校验 UA/Referer 导致订阅无效：
 * ```
 * http://host/iptv.m3u|User-Agent=okhttp/4.9
 * http://host/live.txt|UA=VLC/3.0.20|Referer=http://host/
 * ```
 * 键名大小写不敏感：`User-Agent` / `UA` 归一为 User-Agent，其余支持的键为
 * `Referer`、`Origin`、`Cookie`。真实 URL 中几乎不会出现 `|`，因此用它做分隔符最安全。
 */
data class LiveSourceEndpoint(
    /** 去掉参数段后的真实请求地址。 */
    val url: String,
    /** 参数里声明的请求头；空表示按全局 UA / 内置默认请求。 */
    val headers: Map<String, String>,
)

/** 参数段支持的头部键 → 规范化键名；键匹配忽略大小写。 */
private val LIVE_SOURCE_PARAM_KEYS = mapOf(
    "user-agent" to "User-Agent",
    "ua" to "User-Agent",
    "referer" to "Referer",
    "origin" to "Origin",
    "cookie" to "Cookie",
)

/**
 * 解析订阅地址为「真实 URL + 行内头部」。
 * 非法输入（空串、非 http 地址）原样返回空头部，调用方按无参数处理；
 * 重复键取最后一次，含换行的值丢弃该参数（HTTP 头不允许换行）。
 * 参数段没有解析出任何合法头部时（如 URL 自带未编码的 `|`），整串按原样返回，不截断真实地址。
 */
fun parseLiveSourceEndpoint(raw: String): LiveSourceEndpoint {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return LiveSourceEndpoint(url = raw, headers = emptyMap())
    val separator = trimmed.indexOf('|')
    if (separator <= 0) return LiveSourceEndpoint(url = trimmed, headers = emptyMap())
    val url = trimmed.substring(0, separator).trim()
    if (!url.startsWith("http", ignoreCase = true)) {
        // 不是「URL|参数」的合法形状：按无参数处理，保持与旧行为一致。
        return LiveSourceEndpoint(url = trimmed, headers = emptyMap())
    }
    val headers = buildMap {
        trimmed.substring(separator + 1).split('|').forEach { segment ->
            val eq = segment.indexOf('=')
            if (eq <= 0) return@forEach
            val key = segment.substring(0, eq).trim()
            val value = segment.substring(eq + 1).trim()
            val normalized = LIVE_SOURCE_PARAM_KEYS[key.lowercase()] ?: return@forEach
            if (value.isEmpty() || value.any { it == '\r' || it == '\n' }) return@forEach
            put(normalized, value)
        }
    }
    if (headers.isEmpty()) return LiveSourceEndpoint(url = trimmed, headers = emptyMap())
    return LiveSourceEndpoint(url = url, headers = headers)
}
