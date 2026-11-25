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
            .replace(CCTV_NUM_PREFIX_REGEX, "")
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

// 预编译：TXT 逐行解析与 M3U 每条 EXTINF 都会用到，逐次构造正则会把大直播源解析退化成 CPU 密集任务。
private val CCTV_NUM_PREFIX_REGEX = Regex("^\\d+(?=CCTV-)")

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
        // 属性部分在 `#EXTM3U` 之后；直接用 parseAttributes 解析（正确处理引号内冒号/逗号）。
        val attrsLine = trimmed.substringAfter("#EXTM3U").trim().removePrefix(":").trim()
        val attrs = M3uTagLexer.parseAttributes(attrsLine)
        for (attribute in M3U_EPG_ATTRIBUTES) {
            val value = attrs[attribute.lowercase()]
                ?.split(',')
                ?.map { it.trim().trim('"') }
                ?.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            if (value != null) return value
        }
    }
    return null
}

private fun parseM3uLiveChannels(raw: String): List<LiveChannel> {
    val rawLines = raw.lines()
    // T2-15：生产路径统一交给 M3uTagLexer —— tvg-id 作为频道身份、缺失回落归一化名字、
    // 重名频道折叠成一条频道多条线路、畸形行只计 skipped 不抛异常。
    // 旧的 EXTINF 属性解析仍复用 parseAttributes（见 toM3uChannelMetadata / collectM3uLineExtras），
    // 这里只做「lexer 结果 → LiveChannel」的适配器，保留展示名/台标/请求头/#EXTGRP 语义。
    val lexed = M3uTagLexer.parse(rawLines)
    val extras = collectM3uLineExtras(rawLines)
    val channels = linkedMapOf<LiveChannelKey, AggregatedChannel>()

    lexed.lines.forEachIndexed { index, lexedLine ->
        val extra = extras.getOrNull(index) ?: return@forEachIndexed
        val definition = lexed.channels.firstOrNull { it.key == lexedLine.channelKey }
            ?: return@forEachIndexed
        val groupName = extra.groupOverride
            ?: definition.group.ifBlank { DEFAULT_M3U_GROUP_NAME }
        val channelName = extra.channelName.ifBlank { definition.name }
        // T2-15：tvg-id 存在时以它为频道身份（不同台标名也能折叠、同名不同 id 不误合并）；
        // 缺失时回落旧口径的频道名，保持既有 tvg-name 聚合语义不破坏调用方。
        val identity = extra.tvgId.takeIf { it.isNotBlank() }
            ?.let { "tvg:${it.trim().lowercase()}" }
            ?: "name:$channelName"
        val key = LiveChannelKey(groupName = groupName, channelName = identity)
        val aggregated = channels.getOrPut(key) { AggregatedChannel(displayName = channelName) }
        val lines = aggregated.lines
        if (lines.none { it.url == lexedLine.url }) {
            lines += LiveChannelLine(
                name = extra.lineName.ifBlank { "线路${lines.size + 1}" },
                url = lexedLine.url,
                headers = extra.headers,
            )
        }
        // 同一频道多行时取第一个非空 tvg-logo / tvg-id。
        if (aggregated.logo.isBlank()) aggregated.logo = extra.logo
        if (aggregated.tvgId.isBlank()) aggregated.tvgId = extra.tvgId
    }

    // #72：同一 URL 在多分组重复出现（如"全部"与具体分类均含该频道）时只保留首个，
    // 避免生成大量必播放失败的重复频道。以「该频道全部线路 URL 列表」为去重键。
    val deduped = ArrayList<LiveChannel>(channels.size)
    val seenChannelUrls = mutableSetOf<List<String>>()
    channels.entries.forEach { entry ->
        val lines = entry.value.lines.toList()
        if (lines.isEmpty()) return@forEach
        if (!seenChannelUrls.add(lines.map { it.url })) return@forEach
        deduped += LiveChannel(
            number = deduped.size + 1,
            groupName = entry.key.groupName,
            name = entry.value.displayName.ifBlank { entry.key.channelName },
            lines = lines,
            logo = entry.value.logo,
            tvgId = entry.value.tvgId,
        )
    }
    return deduped
}

/** lexer 不承载的展示信息：旧口径频道名、台标、行名、请求头与 #EXTGRP 分组覆盖。 */
private data class M3uLineExtras(
    val channelName: String,
    val lineName: String,
    val logo: String,
    val tvgId: String,
    val headers: Map<String, String>,
    val groupOverride: String?,
)

/**
 * 按 M3uTagLexer.parse 收线路的顺序补齐展示信息。
 * 属性解析仍走 [M3uTagLexer.parseAttributes] / [String.toM3uChannelMetadata]，不另起一套词法器；
 * 与 lexer 保持同一「URL 行归属」判定（显示名为空则不产生线路，其他 # 扩展 tag 不清空频道）。
 */
private fun collectM3uLineExtras(rawLines: List<String>): List<M3uLineExtras> {
    val extras = mutableListOf<M3uLineExtras>()
    var currentMetadata: M3uChannelMetadata? = null
    var pendingHeaders: Map<String, String> = emptyMap()
    var pendingGroup: String? = null

    rawLines.forEachIndexed { index, rawLine ->
        val line = (if (index == 0) rawLine.removePrefix("\uFEFF") else rawLine).trim()
        when {
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                val body = line.substringAfter(":", "")
                val attrs = M3uTagLexer.parseAttributes(body)
                // 判定必须与 lexer 完全一致（同一 normalizeText，不额外 cleanHtml），
                // 否则 extras 与 lexed.lines 的下标会错位，把元数据挂到别的线路上。
                val displayName = attrs["name"]?.let(::normalizeText).orEmpty()
                // 与 lexer 一致：逗号后的显示名为空则该 EXTINF 不产生线路。
                currentMetadata = if (displayName.isBlank()) null else (
                    line.toM3uChannelMetadata() ?: M3uChannelMetadata(
                        groupName = DEFAULT_M3U_GROUP_NAME,
                        channelName = displayName,
                        lineName = displayName,
                    )
                    )
                pendingGroup = null
                pendingHeaders = emptyMap()
            }
            line.startsWith("#EXTGRP", ignoreCase = true) -> {
                // 兼容 #EXTGRP:组名 与 #EXTGRP=组名 两种分隔写法（部分源用等号）。
                val body = if (line.contains(':')) line.substringAfter(':') else line.substringAfter('=')
                pendingGroup = body.trim().trim('"').ifBlank { null }
            }
            line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                // 例：#EXTVLCOPT:http-user-agent=Mozilla/5.0
                val header = line.toExtVlcOptHeaderOrNull()
                if (header != null) pendingHeaders = pendingHeaders + header
            }
            line.startsWith("#") -> Unit
            // 与 lexer 的 looksLikeUrl 同口径：先折全角空格再判定，保证线路下标一一对齐。
            line.replace('\u3000', ' ').isPlayableUrl() -> {
                val metadata = currentMetadata
                if (metadata != null) {
                    extras += M3uLineExtras(
                        channelName = metadata.channelName,
                        lineName = metadata.lineName,
                        logo = metadata.logo,
                        tvgId = metadata.tvgId,
                        headers = pendingHeaders,
                        groupOverride = pendingGroup,
                    )
                }
                pendingHeaders = emptyMap()
            }
            else -> Unit // lexer 同样不清空 current，只把该行计入 skipped。
        }
    }
    return extras
}

private fun String.toM3uChannelMetadata(): M3uChannelMetadata? {
    val body = substringAfter(":", missingDelimiterValue = "")
    val attrs = M3uTagLexer.parseAttributes(body)
    val displayName = attrs["name"]?.let(::cleanHtml)?.trim()?.ifBlank { null } ?: ""
    val channelNameRaw = attrs["tvg-name"]
        ?.let(::cleanHtml)?.trim()?.ifBlank { null }
        ?: attrs["tvg-id"]
        ?.let(::cleanHtml)?.trim()?.ifBlank { null }
    val channelName = channelNameRaw ?: displayName.ifEmpty { return null }
    return M3uChannelMetadata(
        groupName = attrs["group-title"]
            ?.let(::normalizeText)
            ?.ifBlank { null }
            ?: attrs["group"]
                ?.let(::normalizeText)
                ?.ifBlank { null }
            ?: DEFAULT_M3U_GROUP_NAME,
        channelName = channelName,
        // EXTINF 逗号后的展示名通常是"频道名 高清/标太"这类人工标签，
        // 聚合为同一频道时用它做线路名，比"线路N"更可辨。
        lineName = displayName.ifEmpty { "" },
        // 台标字段各家源命名不统一，按常见度依次尝试。
        logo = attrs["tvg-logo"]
            ?.ifBlank { null }
            ?: attrs["tvg-icon"]
                ?.ifBlank { null }
            ?: attrs["logo"]
            ?: "",
        tvgId = attrs["tvg-id"]?.ifBlank { null } ?: "",
    )
}

private fun normalizeText(raw: String): String =
    raw.replace('\u3000', ' ').trim()

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
    /** M3U adapter 的展示名：身份键用 tvg-id/name 折叠，展示仍用源里的频道名。 */
    var displayName: String = "",
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
    // `$` 两侧都可能是地址：`地址$线路元数据`（真实源最常见）或 `线路名$地址`。
    // 只检查 `$` 之后会把「同行多线路」的第 2..N 条线路拼接进前一条地址里。
    val before = substringBefore('$').trim()
    val after = if (contains('$')) substringAfter('$').trim() else ""
    return before.isPlayableUrl() || after.isPlayableUrl()
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
        val afterLast = part.substringAfterLast('$').trim()
        if (afterLast.isPlayableUrl()) {
            val lineName = part.substringBeforeLast('$').trim()
            return ParsedLiveChannelLine(
                channelName = channelName,
                lineName = normalizeLineName(lineName),
                url = afterLast,
            )
        }
        // 与 startsNewLinePart 的口径对齐：它用第一个 `$` 判定「新线路开头」。
        // 当地址本身含多个 `$`（如 `线路名$https://a/b?x=1$foo`）时，
        // substringAfterLast 拿到的是尾部元数据而非 URL，旧逻辑会静默丢弃整条线路。
        // 这里补一条兜底：第一个 `$` 之后若是可播放地址，就以它为准。
        val afterFirst = part.substringAfter('$').trim()
        if (afterFirst.isPlayableUrl()) {
            return ParsedLiveChannelLine(
                channelName = channelName,
                lineName = normalizeLineName(part.substringBefore('$').trim()),
                url = afterFirst,
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

// 前缀只到 `『`：去掉后剩下的应是「线路N」整体。
// 旧值含「『线路」，去前缀后只剩编号数字 "3"，startsWith("线路") 永远为假，
// 命中 `first.startsWith(prefix) -> ""` 分支导致真实源线路名从未生效（退化成重新编号）。
private const val LIVE_LINE_METADATA_PREFIX = "LR•IPV4•29『"

/** TXT 源没有 `#genre#` 分组头（如内置的 iptv.txt）时的默认分组名。 */
private const val DEFAULT_TXT_GROUP_NAME = "电视直播"

/** M3U 源没有 `group-title` 时的默认分组名。 */
private const val DEFAULT_M3U_GROUP_NAME = "未分组"
