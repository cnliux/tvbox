package com.tvbox.app.domain

/**
 * HTTP 请求头的公共清洗与拆分工具。
 *
 * 之前散在直播探测、电视直播、手机直播、平台直播四处：
 * 过滤谓词（空键/空值/含换行）与「User-Agent 单独拆出」的三行样板各写了一遍，
 * 现收敛为两个纯函数，行为与原实现逐字一致（含 CRLF 注入防护）。
 */

/** 过滤非法请求头：空键/空值/含 `\r` 或 `\n`（HTTP 头禁止 CRLF 注入）一律丢弃。 */
fun Map<String, String>.sanitizedForHttp(): Map<String, String> =
    filter { (key, value) ->
        key.isNotBlank() && value.isNotBlank() && value.none { it == '\r' || it == '\n' }
    }

/**
 * 把 User-Agent（大小写不敏感，取第一条命中）从头部中拆出，
 * 返回 `(ua, 其余头)`。ExoPlayer 等播放器把 UA 当受限请求头，必须走
 * `setUserAgent` 单独下发，其余头才允许进通用请求属性。
 * 没有命中时返回 `(null, 原头部)`。
 */
fun Map<String, String>.partitionUserAgent(): Pair<String?, Map<String, String>> {
    val uaEntry = entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
    val rest = if (uaEntry == null) {
        this
    } else {
        filterKeys { key -> !key.equals("User-Agent", ignoreCase = true) }
    }
    return uaEntry?.value to rest
}
