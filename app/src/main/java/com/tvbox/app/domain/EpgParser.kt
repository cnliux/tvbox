package com.tvbox.app.domain

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * XMLTV 节目单解析（手写正则抽取，项目不引入 XML 依赖）。
 *
 * 产出的 Map：key = XMLTV `channel id`，**并为每个 `display-name` 另建一个 key**——
 * 直播源里的 `tvg-id` 常常对不上 XMLTV 的 id，按显示名匹配才是实际可行的路径；
 * value = 该频道节目列表，按 startMs 升序。
 *
 * 容错约定：整体 runCatching，异常时返回已解析部分；
 * 文本超过 40MB 时停止累积（截断解析）；单条时间解析失败则跳过该条。
 */
private const val EPG_MAX_TEXT_CHARS = 40 * 1024 * 1024

/** stop 缺失/无法解析时的兜底节目时长（30 分钟），保证"正在播出"仍能命中。 */
private const val EPG_FALLBACK_PROGRAM_MS = 30L * 60L * 1000L

/** XMLTV 时间戳候选格式，按常见度依次尝试（`yyyyMMddHHmmss` ± 时区段 / 毫秒 / 精度降级）。 */
private val EPG_TIME_PATTERNS = listOf(
    "yyyyMMddHHmmss Z",
    "yyyyMMddHHmmssZ",
    "yyyyMMddHHmmss.SSS Z",
    "yyyyMMddHHmmss",
    "yyyyMMddHHmm",
    "yyyyMMdd",
)

fun parseXmltvEpg(raw: String): Map<String, List<EpgProgram>> {
    var channelsById = linkedMapOf<String, MutableList<String>>()
    var programmes = linkedMapOf<String, MutableList<EpgProgram>>()
    return runCatching {
        val text = if (raw.length > EPG_MAX_TEXT_CHARS) raw.substring(0, EPG_MAX_TEXT_CHARS) else raw
        parseXmltvChannels(text, channelsById)
        parseXmltvProgrammes(text, programmes)
        // 按 id 键 + display-name 键双写；display-name 撞车时合并去重。
        val result = linkedMapOf<String, MutableList<EpgProgram>>()
        programmes.forEach { (id, list) ->
            // 源里存在没有 <channel> 声明的 programme：id 键照旧保留。
            result.mergeEpg(id, list)
        }
        channelsById.forEach { (id, displayNames) ->
            val list = programmes[id] ?: return@forEach
            displayNames.forEach { name -> result.mergeEpg(name, list) }
        }
        result.mapValues { (_, list) -> list.sortedBy { it.startMs } }
    }.getOrElse {
        // 异常兜底：至少把已解析的 id 键部分排序返回。
        programmes.mapValues { (_, list) -> list.sortedBy { it.startMs } }
    }
}

/**
 * 按优先级为频道查找节目单：
 * 频道名精确（忽略大小写与全/半角空格）→ 频道名去空格 → logoId → 分组名+频道名组合键。
 * 命中返回该列表，否则 emptyList。
 */
fun findEpgPrograms(
    map: Map<String, List<EpgProgram>>,
    groupName: String,
    channelName: String,
    logoId: String = "",
): List<EpgProgram> {
    if (map.isEmpty()) return emptyList()
    if (channelName.isNotBlank()) {
        // 1. 频道名精确：忽略大小写与全/半角空格（首尾与内部空格均规范化）。
        findEpgByKey(map, channelName, normalize = { collapseEpgSpaces(it.lowercase()) })
            ?.let { return it }
        // 2. 频道名彻底去空格：兼容"CCTV 1 综合"与"CCTV1综合"这类差异。
        findEpgByKey(map, channelName, normalize = { stripEpgSpaces(it.lowercase()) })
            ?.let { return it }
    }
    if (logoId.isNotBlank()) {
        map[logoId.trim()].takeIf { it?.isNotEmpty() == true }?.let { return it }
    }
    if (groupName.isNotBlank() && channelName.isNotBlank()) {
        // 4. 分组+频道名组合键：兼容外部以 "分组#频道" 形式建键的节目单。
        for (combined in listOf("$groupName#$channelName", "$groupName/$channelName", "$groupName|$channelName")) {
            findEpgByKey(map, combined, normalize = { stripEpgSpaces(it.lowercase()) })
                ?.let { return it }
        }
    }
    return emptyList()
}

private fun parseXmltvChannels(text: String, into: MutableMap<String, MutableList<String>>) {
    val channelRegex = Regex("<channel\\b[^>]*>(.*?)</channel>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val nameRegex = Regex("<display-name\\b[^>]*>([^<]*)</display-name>", RegexOption.IGNORE_CASE)
    for (match in channelRegex.findAll(text)) {
        val id = extractXmltvAttribute(match.value.substringBefore('>'), "id") ?: continue
        if (id.isBlank()) continue
        val names = nameRegex.findAll(match.groupValues[1])
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        if (names.isNotEmpty()) into.getOrPut(id) { mutableListOf() }.addAll(names)
    }
}

private fun parseXmltvProgrammes(text: String, into: MutableMap<String, MutableList<EpgProgram>>) {
    // 兼容自闭合 `<programme .../>`（无 title 的占位条目直接跳过）。
    val programmeRegex = Regex(
        "<programme\\b([^>]*?)(/>|>(.*?)</programme>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val titleRegex = Regex("<title\\b[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
    for (match in programmeRegex.findAll(text)) {
        val attrs = match.groupValues[1]
        val channel = extractXmltvAttribute(attrs, "channel") ?: continue
        if (channel.isBlank()) continue
        val startMs = parseXmltvTime(extractXmltvAttribute(attrs, "start")) ?: continue
        val stopMs = parseXmltvTime(extractXmltvAttribute(attrs, "stop")) ?: (startMs + EPG_FALLBACK_PROGRAM_MS)
        // <sub-title> / <desc> 忽略，只取第一个 <title>。
        val inner = match.groups[3]?.value.orEmpty()
        val title = titleRegex.find(inner)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (title.isBlank()) continue
        into.getOrPut(channel.trim()) { mutableListOf() } += EpgProgram(title, startMs, stopMs)
    }
}

private fun extractXmltvAttribute(attrs: String, name: String): String? {
    val match = Regex("(?:^|\\s)${Regex.escape(name)}\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        .find(attrs)
        ?: Regex("(?:^|\\s)${Regex.escape(name)}\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(attrs)
    return match?.groupValues?.getOrNull(1)?.trim()
}

private fun parseXmltvTime(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // 毫秒段（`.500`）先行剥离，避免逐格式拼接。
    val normalized = value.replaceFirst(Regex("^(\\d{8}\\d{6})\\.\\d+"), "$1")
    for (pattern in EPG_TIME_PATTERNS) {
        val parsed = runCatching {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.isLenient = false
            format.parse(normalized)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

/** 合并进结果表：按 (start, stop, title) 去重，容量受限于源数据。 */
private fun MutableMap<String, MutableList<EpgProgram>>.mergeEpg(key: String, list: List<EpgProgram>) {
    if (key.isBlank() || list.isEmpty()) return
    val target = getOrPut(key.trim()) { mutableListOf() }
    for (program in list) {
        if (target.none { it.startMs == program.startMs && it.stopMs == program.stopMs && it.title == program.title }) {
            target += program
        }
    }
}

/** 大小写无关 + 全/半角空格折叠为单个半角空格并去首尾。 */
private fun collapseEpgSpaces(value: String): String =
    value.replace('\u3000', ' ').trim().replace(Regex("\\s+"), " ")

/** 彻底移除全/半角空格。 */
private fun stripEpgSpaces(value: String): String =
    value.replace('\u3000', ' ').filterNot { it.isWhitespace() }

/** 按规范化键在 Map 里找首个非空命中（key 数量在数千级，线性扫描可接受）。 */
private fun findEpgByKey(
    map: Map<String, List<EpgProgram>>,
    wanted: String,
    normalize: (String) -> String,
): List<EpgProgram>? {
    val target = normalize(wanted)
    if (target.isBlank()) return null
    return map.entries.firstOrNull { entry ->
        normalize(entry.key) == target && entry.key.isNotBlank()
    }?.value?.takeIf { it.isNotEmpty() }
}
