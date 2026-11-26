package com.tvbox.app.domain

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * XMLTV 节目单解析（手写正则抽取，项目不引入 XML 依赖）。
 *
 * 产出的 Map：key = XMLTV `channel id`，**并为每个 `display-name` 另建一个 key**——
 * 直播源里的 `tvg-id` 常常对不上 XMLTV 的 id，按显示名匹配才是实际可行的路径；
 * value = 该频道节目列表，按 startMs 升序。
 *
 * 容错约定：频道段与节目段分别容错，任一段解析异常时仍用已解析部分（含 display-name 别名）建表；
 * 文本超过 40MB 时停止累积（截断解析）；单条时间解析失败则跳过该条。
 */
private const val EPG_MAX_TEXT_CHARS = 40 * 1024 * 1024

/**
 * 单条 `<programme>` 片段长度上限：正常节目条目仅数 KB；远超阈值说明源里塞入了
 * 巨型实体文本或被污染的片段，整段丢弃以免正则累积与后续 title 抽取退化成 CPU 密集任务。
 */
private const val EPG_MAX_PROGRAMME_CHARS = 1 * 1024 * 1024

/** XMLTV 不允许 `<channel>` / `<programme>` 自嵌套，深度超过即视为畸形/攻击源，截断后续解析。 */
private const val EPG_MAX_DEPTH = 2

/** XMLTV 不使用 DTD：DOCTYPE 与 ENTITY 声明一律拒绝（实体攻击 / XXE 防护）。 */
private const val EPG_MAX_ENTITY_DECLARATIONS = 0

/** DOCTYPE 声明（大小写不敏感）命中即拒绝整份 EPG：DTD 可声明外部实体，存在 XXE 风险。 */
private val EPG_DOCTYPE_REGEX = Regex("<!\\s*DOCTYPE", RegexOption.IGNORE_CASE)

/** ENTITY 声明（大小写不敏感）：billion laughs 类嵌套实体在正则前后都可能造成回溯爆炸。 */
private val EPG_ENTITY_REGEX = Regex("<!\\s*ENTITY", RegexOption.IGNORE_CASE)

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

// SimpleDateFormat 构造成本高且非线程安全：节目单常有数万条 programme，
// 逐条 new 6 个格式对象会把解析退化成 CPU 密集任务。按线程缓存一组预建格式复用，
// isLenient=false 与原实现一致；仅「解析」是单线程循环，ThreadLocal 保证并发安全。
// 缓存按「EPG 时区」分组：无时区后缀的时间戳按用户配置的源时区解释（epgTimezone），
// 留空则跟随设备默认时区（空键 ""）。
private val EPG_TIME_FORMATS: ThreadLocal<MutableMap<String, List<SimpleDateFormat>>> =
    ThreadLocal.withInitial { mutableMapOf<String, List<SimpleDateFormat>>() }

private fun epgTimeFormats(timezoneId: String): List<SimpleDateFormat> =
    EPG_TIME_FORMATS.get().getOrPut(timezoneId) {
        val zone = epgTimeZone(timezoneId)
        EPG_TIME_PATTERNS.map { pattern ->
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                if (zone != null) timeZone = zone
            }
        }
    }

/**
 * 解析用户配置的 EPG 时区 ID：空串返回 null（跟随设备）；
 * `TimeZone.getTimeZone` 对未知 ID 静默回退 GMT，这里把疑似拼错的值也按未配置处理，
 * 避免一次手滑让节目时间整体偏移数小时。
 */
private fun epgTimeZone(timezoneId: String): TimeZone? {
    val trimmed = timezoneId.trim()
    if (trimmed.isEmpty()) return null
    val zone = runCatching { TimeZone.getTimeZone(trimmed) }.getOrNull() ?: return null
    if (zone.id == "GMT" &&
        !trimmed.startsWith("GMT", ignoreCase = true) &&
        !trimmed.startsWith("UT", ignoreCase = true)
    ) {
        return null
    }
    return zone
}

// 预编译正则：XMLTV 常有数万条 programme，逐条构造 Regex 会把解析退化成 CPU 密集任务。
private val EPG_CHANNEL_REGEX =
    Regex("<channel\\b[^>]*>(.*?)</channel>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val EPG_DISPLAY_NAME_REGEX =
    Regex("<display-name\\b[^>]*>([^<]*)</display-name>", RegexOption.IGNORE_CASE)
private val EPG_PROGRAMME_REGEX = Regex(
    "<programme\\b([^>]*?)(/>|>(.*?)</programme>)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val EPG_TITLE_REGEX = Regex("<title\\b[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
private val EPG_ATTRIBUTE_REGEX = Regex("""(?:^|\s)([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val EPG_MILLIS_REGEX = Regex("^(\\d{8}\\d{6})\\.\\d+")
/** XMLTV 常见写法 `+08:00` → SimpleDateFormat `Z` 需要的 `+0800`；不归一化会被无时区格式吞掉后缀。 */
private val EPG_COLON_ZONE_REGEX = Regex("([+-]\\d{2}):(\\d{2})$")
/** UTC 字面量 `Z` 后缀（如 `20240101120000Z`）→ `+0000`，避免字面量 Z 无法被 RFC822 Z 格式消费。 */
private val EPG_UTC_LITERAL_ZONE_REGEX = Regex("Z$")
private val EPG_SPACES_REGEX = Regex("\\s+")

/**
 * [timezoneId]：无时区后缀时间戳（`20240101120000`）的解释时区（IANA ID，如 `Asia/Shanghai`）；
 * 空或非法值跟随设备默认时区。带显式偏移的时间戳不受影响。
 */
fun parseXmltvEpg(raw: String, timezoneId: String = ""): Map<String, List<EpgProgram>> {
    // 入口即拒绝 DTD：DOCTYPE / ENTITY 命中任一即返回空表（与本项目「解析失败返回部分结果」风格一致），
    // 绝不展开实体，避免 XXE 与 billion laughs 在后续正则匹配中造成回溯爆炸。
    if (EPG_DOCTYPE_REGEX.containsMatchIn(raw)) return emptyMap()
    if (EPG_ENTITY_REGEX.containsMatchIn(raw)) return emptyMap()
    val channelsById = linkedMapOf<String, MutableList<String>>()
    val programmes = linkedMapOf<String, MutableList<EpgProgram>>()
    val text = if (raw.length > EPG_MAX_TEXT_CHARS) raw.substring(0, EPG_MAX_TEXT_CHARS) else raw
    // 嵌套深度防护：`<channel>`/`<programme>` 在 XMLTV 里不允许自嵌套，超过 2 层即视为畸形源，
    // 截断到首个异常嵌套位置，保留前面已合法的部分（与「超大文本截断」同风格）。
    val safeText = truncateEpgByDepth(text)
    // 两段分别容错：畸形源导致后段抛异常时，前段已解析的 id 与 display-name 别名不再一起丢失。
    runCatching { parseXmltvChannels(safeText, channelsById) }
    runCatching { parseXmltvProgrammes(safeText, programmes, timezoneId) }
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
    return result.mapValues { (_, list) -> list.sortedBy { it.startMs } }
}

/**
 * 嵌套深度截断：扫描开始/结束标签，`<channel>` / `<programme>` 任意一个一旦超过 [EPG_MAX_DEPTH]
 * 层，立即截断并返回前缀；未超限则原样返回（`<tv>` 根 + 一层 channel/programme = 深度 2）。
 */
private fun truncateEpgByDepth(text: String): String {
    // 分组：group1 = 闭合斜杠 `/`（`</channel>` 时非空）；group2 = 标签名；group3 = 自闭合斜杠（`/>` 时非空）。
    val tagRegex = Regex("<(/?)\\s*(channel|programme)\\b[^>]*?(/?)>", RegexOption.IGNORE_CASE)
    var depth = 0
    for (match in tagRegex.findAll(text)) {
        val (closeSlash, _, selfClosingSlash) = match.destructured
        if (selfClosingSlash == "/") continue
        if (closeSlash == "/") {
            if (depth > 0) depth--
            continue
        }
        depth++
        if (depth > EPG_MAX_DEPTH) return text.substring(0, match.range.first)
    }
    return text
}

/**
 * 按优先级为频道查找节目单：
 * 频道名精确（忽略大小写与全/半角空格）→ 频道名去空格 → 频道 id（`tvg-id`）→ 分组名+频道名组合键。
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
    for (match in EPG_CHANNEL_REGEX.findAll(text)) {
        val id = xmltvAttributes(match.value.substringBefore('>'))["id"] ?: continue
        if (id.isBlank()) continue
        val names = EPG_DISPLAY_NAME_REGEX.findAll(match.groupValues[1])
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        if (names.isNotEmpty()) into.getOrPut(id) { mutableListOf() }.addAll(names)
    }
}

private fun parseXmltvProgrammes(
    text: String,
    into: MutableMap<String, MutableList<EpgProgram>>,
    timezoneId: String,
) {
    // 兼容自闭合 `<programme .../>`（无 title 的占位条目直接跳过）。
    for (match in EPG_PROGRAMME_REGEX.findAll(text)) {
        // P28：单条 programme 长度上限——超阈值说明被塞入巨型实体或污染片段，直接跳过该条。
        if (match.value.length > EPG_MAX_PROGRAMME_CHARS) continue
        val attrs = xmltvAttributes(match.groupValues[1])
        val channel = attrs["channel"] ?: continue
        if (channel.isBlank()) continue
        val startMs = parseXmltvTime(attrs["start"], timezoneId) ?: continue
        // 异常 XMLTV 里 stop 可能早于 start；非法区间按兜底时长处理，避免节目永远不命中"正在播出"。
        val stopMs = parseXmltvTime(attrs["stop"], timezoneId)
            ?.takeIf { it > startMs }
            ?: (startMs + EPG_FALLBACK_PROGRAM_MS)
        // <sub-title> / <desc> 忽略，只取第一个 <title>。
        val inner = match.groups[3]?.value.orEmpty()
        val title = EPG_TITLE_REGEX.find(inner)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (title.isBlank()) continue
        into.getOrPut(channel.trim()) { mutableListOf() } += EpgProgram(title, startMs, stopMs)
    }
}

/** 一次性抽出标签上的全部属性，避免每个 programme 为每个字段重新编译正则。 */
private fun xmltvAttributes(fragment: String): Map<String, String> =
    EPG_ATTRIBUTE_REGEX.findAll(fragment).associate { match ->
        val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
        match.groupValues[1].lowercase() to value.trim()
    }

private fun parseXmltvTime(raw: String?, timezoneId: String): Long? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // 毫秒段（`.500`）先行剥离，再把带冒号的时区归一化为 `Z` 格式。
    val withoutMillis = value.replaceFirst(EPG_MILLIS_REGEX, "$1")
    val normalized = withoutMillis.replace(EPG_COLON_ZONE_REGEX, "$1$2")
        // XMLTV 常见的 UTC 字面量 `Z` 后缀（如 20240101120000Z）：SimpleDateFormat 的
        // Z 是 RFC822 ±HHMM 数值偏移，无法消费字面量 Z；不归一化会因「必须完整消费」
        // 被整条丢弃，这里统一改写为 +0000 后交给带时区格式解析。
        .replace(EPG_UTC_LITERAL_ZONE_REGEX, "+0000")
    // 复用按线程缓存的格式组：每条 programme 原本会 new 6 个 SimpleDateFormat。
    for (format in epgTimeFormats(timezoneId)) {
        // 必须完整消费字符串：SimpleDateFormat.parse(String) 会静默忽略尾部多余字符，
        // 曾导致 `+08:00` 被当成无时区时间按设备本地时区解析（偏移数小时）。
        val position = ParsePosition(0)
        val parsed = runCatching { format.parse(normalized, position) }.getOrNull()
        if (parsed != null && position.index == normalized.length) return parsed.time
    }
    return null
}

private data class EpgProgramKey(val startMs: Long, val stopMs: Long, val title: String)

/** 合并进结果表：按 (start, stop, title) 去重，用 HashSet 避免 display-name 别名合并退化成 O(n²)。 */
private fun MutableMap<String, MutableList<EpgProgram>>.mergeEpg(key: String, list: List<EpgProgram>) {
    if (key.isBlank() || list.isEmpty()) return
    val target = getOrPut(key.trim()) { mutableListOf() }
    val seen = HashSet<EpgProgramKey>(target.size + list.size)
    target.forEach { seen += EpgProgramKey(it.startMs, it.stopMs, it.title) }
    for (program in list) {
        if (seen.add(EpgProgramKey(program.startMs, program.stopMs, program.title))) target += program
    }
}

/** 大小写无关 + 全/半角空格折叠为单个半角空格并去首尾。 */
private fun collapseEpgSpaces(value: String): String =
    value.replace('\u3000', ' ').trim().replace(EPG_SPACES_REGEX, " ")

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
