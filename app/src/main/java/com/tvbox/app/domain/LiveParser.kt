package com.tvbox.app.domain

/**
 * 直播源解析（TXT 与 M3U）。
 *
 * TXT 约定：`频道名,地址` 或 `频道名,线路名$地址,线路名$地址`；
 * 常见变体是 `频道名,地址$LR•IPV4•29『线路N』` —— `$` 之后是**线路名元数据**，
 * 地址在 `$` 之前。少数源会把多条地址写在同一行用逗号分隔。
 * M3U 约定：`#EXTINF` 元数据行 + 紧随其后的地址行，支持 `#EXTVLCOPT` 附带请求头。
 */
fun parseLiveChannels(raw: String): List<LiveChannel> {
    val normalized = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .removePrefix("﻿")
    if (normalized.lineSequence().any {
            val t = it.trimStart()
            t.startsWith("#EXTINF", ignoreCase = true) || t.startsWith("#EXTM3U", ignoreCase = true)
        }
    ) {
        return parseM3uLiveChannels(normalized)
    }
    // 目录页/错误页（jsDelivr、gh-proxy 的 HTML 索引）不是直播源，直接判空，
    // 避免把 HTML 里的字符串当成频道名，产生大量"必然播放失败"的假频道。
    if (looksLikeHtml(normalized)) return emptyList()

    val channels = linkedMapOf<LiveChannelKey, AggregatedChannel>()
    var currentGroupName: String? = null

    normalized.lineSequence().forEach { rawLine ->
        // 部分源用全角逗号分隔频道名与地址；URL 内不会出现全角逗号，统一规范化最安全。
        val line = rawLine.trim().replace('，', ',')
        if (line.isBlank()) return@forEach

        val commaIndex = line.indexOf(',')
        if (commaIndex <= 0 || commaIndex == line.lastIndex) return@forEach

        val name = cleanHtml(line.substring(0, commaIndex))
            .trim()
            .replace(Regex("^\\d+(?=CCTV-)"), "")
        val sourcePart = line.substring(commaIndex + 1).trim()
        if (name.isBlank()) return@forEach

        if (sourcePart.equals("#genre#", ignoreCase = true)) {
            currentGroupName = name
            return@forEach
        }

        // 支持同一行多条地址：频道名,线路名1$地址1,线路名2$地址2,... 或 频道名,地址1,地址2,...
        val groupName = currentGroupName ?: DEFAULT_TXT_GROUP_NAME
        val channelKeyName = cleanHtml(name).trim()
        val key = LiveChannelKey(groupName = groupName, channelName = channelKeyName)
        val aggregated = channels.getOrPut(key) { AggregatedChannel() }
        val lines = aggregated.lines
        for (part in splitTxtLineParts(sourcePart)) {
            val parsedLine = parseTxtLinePart(part, channelKeyName) ?: continue
            if (lines.none { it.url == parsedLine.url }) {
                lines += LiveChannelLine(
                    name = parsedLine.lineName.ifBlank { "线路${lines.size + 1}" },
                    url = parsedLine.url,
                )
            }
        }
    }

    return channels.entries
        .filter { (_, aggregated) -> aggregated.lines.isNotEmpty() }
        .mapIndexed { index, entry ->
            LiveChannel(
                number = index + 1,
                groupName = entry.key.groupName,
                name = entry.key.channelName,
                lines = entry.value.lines.toList(),
                // TXT 源没有台标字段，logo 恒为空。
                logo = entry.value.logo,
            )
        }
}

/** M3U 头部可能出现的 EPG 属性名，按常见度依次尝试。 */
private val M3U_EPG_ATTRIBUTES = listOf("url-tvg", "x-tvg-url", "x-tvg", "tvg-url", "epg")

/**
 * 从 M3U 头部 `#EXTM3U` 行读取节目单地址（`url-tvg` / `x-tvg-url` / `tvg-url` 等）。
 *
 * 只扫描文件前若干行，避免大文件全量正则；多个地址以逗号分隔时取第一个 http 地址。
 * 返回 null 表示源未声明 EPG，调用方应保持「未配置」状态而不是写入空串。
 */
fun extractM3uEpgUrl(raw: String): String? {
    for (line in raw.lineSequence().take(32)) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#EXTM3U", ignoreCase = true)) continue
        for (attribute in M3U_EPG_ATTRIBUTES) {
            val match = Regex(
                "(?:^|\\s)${Regex.escape(attribute)}\\s*=\\s*\"([^\"]+)\"",
                RegexOption.IGNORE_CASE,
            ).find(trimmed) ?: continue
            val value = match.groupValues[1]
                .split(',')
                .map { it.trim().trim('"') }
                .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            if (value != null) return value
        }
    }
    return null
}

private fun parseM3uLiveChannels(raw: String): List<LiveChannel> {
    val channels = linkedMapOf<LiveChannelKey, AggregatedChannel>()
    var pendingChannel: M3uChannelMetadata? = null
    var pendingHeaders: Map<String, String> = emptyMap()
    var urlCount = 0

    raw.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) return@forEach
        when {
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                pendingChannel = line.toM3uChannelMetadata() ?: pendingChannel
            }
            line.startsWith("#EXTGRP", ignoreCase = true) -> {
                val grp = line.substringAfter(':', "").substringAfter('=').trim().trim('"')
                val current = pendingChannel ?: return@forEach
                pendingChannel = current.copy(groupName = grp.ifBlank { current.groupName })
            }
            line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                // 例：#EXTVLCOPT:http-user-agent=Mozilla/5.0
                val header = line.toExtVlcOptHeaderOrNull()
                if (header != null) pendingHeaders = pendingHeaders + header
            }
            line.startsWith("#") -> Unit
            line.isPlayableUrl() -> {
                val metadata = pendingChannel ?: M3uChannelMetadata(
                    groupName = DEFAULT_M3U_GROUP_NAME,
                    channelName = "频道${++urlCount}",
                )
                val key = LiveChannelKey(
                    groupName = metadata.groupName.ifBlank { DEFAULT_M3U_GROUP_NAME },
                    channelName = metadata.channelName.ifBlank { "频道" },
                )
                val aggregated = channels.getOrPut(key) { AggregatedChannel() }
                val lines = aggregated.lines
                if (lines.none { it.url == line }) {
                    lines += LiveChannelLine(
                        name = metadata.lineName.ifBlank { "线路${lines.size + 1}" },
                        url = line,
                        headers = pendingHeaders,
                    )
                }
                // 同一频道多行时取第一个非空 tvg-logo / tvg-id。
                if (aggregated.logo.isBlank()) aggregated.logo = metadata.logo
                if (aggregated.tvgId.isBlank()) aggregated.tvgId = metadata.tvgId
                pendingChannel = null
                pendingHeaders = emptyMap()
            }
            else -> {
                pendingChannel = null
                pendingHeaders = emptyMap()
            }
        }
    }

    return channels.entries.mapIndexed { index, entry ->
        LiveChannel(
            number = index + 1,
            groupName = entry.key.groupName,
            name = entry.key.channelName,
            lines = entry.value.lines.toList(),
            logo = entry.value.logo,
            tvgId = entry.value.tvgId,
        )
    }
}

private fun String.toM3uChannelMetadata(): M3uChannelMetadata? {
    val displayName = substringAfter(',', missingDelimiterValue = "").trim()
    val channelName = extractM3uAttribute("tvg-name")
        .ifBlank { displayName }
        .ifBlank { extractM3uAttribute("tvg-id") }
        .ifBlank { return null }
    return M3uChannelMetadata(
        groupName = extractM3uAttribute("group-title")
            .ifBlank { extractM3uAttribute("group") }
            .ifBlank { DEFAULT_M3U_GROUP_NAME },
        channelName = cleanHtml(channelName).trim(),
        // EXTINF 逗号后的展示名通常是"频道名 高清/标太"这类人工标签，
        // 聚合为同一频道时用它做线路名，比"线路N"更可辨。
        lineName = cleanHtml(displayName).trim(),
        // 台标字段各家源命名不统一，按常见度依次尝试。
        logo = extractM3uAttribute("tvg-logo")
            .ifBlank { extractM3uAttribute("tvg-icon") }
            .ifBlank { extractM3uAttribute("logo") },
        tvgId = extractM3uAttribute("tvg-id"),
    )
}

private fun String.extractM3uAttribute(name: String): String {
    val match = Regex("(?:^|\\s)${Regex.escape(name)}=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
        .find(this)
    return match?.groupValues?.getOrNull(1).orEmpty().trim()
}

/** `#EXTVLCOPT:http-user-agent=xxx` → `User-Agent` to `xxx`；不认识的选项返回 null。 */
private fun String.toExtVlcOptHeaderOrNull(): Pair<String, String>? {
    val option = substringAfter(':', missingDelimiterValue = "").trim()
    if (option.isBlank()) return null
    val (rawKey, value) = option.split('=', limit = 2).let {
        if (it.size < 2) return null else it[0].trim().lowercase() to it.subList(1, it.size).joinToString("=").trim()
    }
    if (value.isBlank()) return null
    val headerKey = when (rawKey) {
        "http-user-agent" -> "User-Agent"
        "http-referrer", "http-referer" -> "Referer"
        "http-cookie" -> "Cookie"
        "http-headers", "http-header" -> return parseRawHttpHeaders(value)
        else -> return null
    }
    return headerKey to value
}

private fun parseRawHttpHeaders(raw: String): Pair<String, String>? {
    // 形如 `Referer=xxx\r\nUser-Agent=yyy`，仅取第一对，避免污染 DataSpec 请求行。
    val first = raw.split("\r\n", "\n", "|").firstOrNull { it.contains('=') } ?: return null
    val key = first.substringBefore('=').trim()
    val value = first.substringAfter('=').trim()
    if (key.isBlank() || value.isBlank()) return null
    return key to value
}

private fun String.isPlayableUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true) ||
        startsWith("rtmp://", ignoreCase = true) || startsWith("rtsp://", ignoreCase = true)

private fun looksLikeHtml(raw: String): Boolean {
    val head = raw.take(4096).lowercase()
    return head.contains("<!doctype html") || head.contains("<html") || head.contains("<head")
}

private data class LiveChannelKey(
    val groupName: String,
    val channelName: String,
)

/** 解析期聚合体：同一频道的多线路 + 第一个非空台标 / tvg-id。 */
private class AggregatedChannel(
    val lines: MutableList<LiveChannelLine> = mutableListOf(),
    var logo: String = "",
    var tvgId: String = "",
)

private data class M3uChannelMetadata(
    val groupName: String,
    val channelName: String,
    val lineName: String = "",
    /** `tvg-logo`（兼容 `tvg-icon` / `logo`）台标 URL；空表示源未提供。 */
    val logo: String = "",
    /** `tvg-id`：节目单匹配用的 XMLTV 频道 id；空表示源未提供。 */
    val tvgId: String = "",
)

private data class ParsedLiveChannelLine(
    val channelName: String,
    val lineName: String,
    val url: String,
)

/**
 * 按逗号切分同一行内的多条线路。
 *
 * 只有当逗号后的片段本身是"新线路开头"（`地址` 或 `线路名$地址`）时才切开，
 * 否则视为地址的一部分——IPTV 源里 `...01.m3u8?fmt=ts2hls,244,01.m3u8&accountinfo=xxx`
 * 这类地址自带逗号，直接 `split(',')` 会把地址截断成 403/404 的死链。
 */
private fun splitTxtLineParts(sourcePart: String): List<String> {
    val pieces = sourcePart.split(',')
    if (pieces.size <= 1) return pieces
    val merged = mutableListOf<String>()
    for (piece in pieces) {
        val trimmed = piece.trim()
        if (trimmed.isBlank()) continue
        if (merged.isEmpty() || trimmed.startsNewLinePart()) {
            merged += trimmed
        } else {
            merged[merged.lastIndex] = merged.last() + "," + trimmed
        }
    }
    return merged
}

private fun String.startsNewLinePart(): Boolean {
    val candidate = if (contains('$')) substringAfter('$') else this
    return candidate.isPlayableUrl()
}

private fun parseTxtLinePart(part: String, channelName: String): ParsedLiveChannelLine? {
    // `$` 两侧都可能是地址：
    // - `地址$LR•IPV4•29『线路N』`：地址在前，`$` 后是线路名元数据（真实源最常见格式）；
    // - `线路名$地址`：地址在后，`$` 前是线路名。
    val before = part.substringBefore('$').trim()
    if (before.isPlayableUrl()) {
        return ParsedLiveChannelLine(
            channelName = channelName,
            lineName = normalizeLineName(part.substringAfter('$', missingDelimiterValue = "")),
            url = before,
        )
    }
    if (part.contains('$')) {
        val after = part.substringAfterLast('$').trim()
        if (after.isPlayableUrl()) {
            val lineName = part.substringBeforeLast('$').trim()
            return ParsedLiveChannelLine(
                channelName = channelName,
                lineName = normalizeLineName(lineName),
                url = after,
            )
        }
    }
    return null
}

/** `LR•IPV4•29『线路3』$LR•IPV4•29『线路4』` 之类元数据 → `线路3`。 */
private fun normalizeLineName(metadata: String): String {
    if (metadata.isBlank()) return ""
    val first = metadata.substringBefore('$').trim()
    if (first.isBlank()) return ""
    val cleaned = first
        .removePrefix(LIVE_LINE_METADATA_PREFIX)
        .trim()
        .trimEnd('』', '】', '」', '\'', '"')
    return when {
        cleaned.startsWith("线路") -> cleaned
        cleaned.isBlank() -> ""
        first.startsWith(LIVE_LINE_METADATA_PREFIX) -> ""
        else -> cleaned
    }
}

private const val LIVE_LINE_METADATA_PREFIX = "LR•IPV4•29『线路"

/** TXT 源没有 `#genre#` 分组头（如内置的 iptv.txt）时的默认分组名。 */
private const val DEFAULT_TXT_GROUP_NAME = "电视直播"

/** M3U 源没有 `group-title` 时的默认分组名。 */
private const val DEFAULT_M3U_GROUP_NAME = "未分组"
