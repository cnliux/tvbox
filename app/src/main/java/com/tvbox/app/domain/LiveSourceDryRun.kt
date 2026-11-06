package com.tvbox.app.domain

/**
 * 直播源导入前的 dry-run 校验摘要。
 *
 * 目的：扫码 / JSON 导入时，源格式错了（HTML 目录页、缺地址、无分组）目前要等用户
 * 进到播放页才发现。这里复用同一个解析器先跑一遍，把"能导入什么、缺什么"直接说清楚。
 *
 * 注意：只做本地结构校验，不发网络请求——校验本身不能拖慢导入，也不能因为网络抖动
 * 误判源不可用（可用性由首播时的线路测试负责）。
 */
data class LiveSourceDryRun(
    /** 解析到的有效频道数。 */
    val channelCount: Int = 0,
    /** 线路��数（一个频道可能有多条线路）。 */
    val lineCount: Int = 0,
    /** 去重后的分组名个数。 */
    val groupCount: Int = 0,
    /** 被判定为无有效线路并丢弃的行数（TXT 中"有名字没地址"这类）。 */
    val droppedLines: Int = 0,
    /** 有频道但全部缺少分组归属（落入兜底分组）的频道数。 */
    val channelsWithoutGroup: Int = 0,
    /** 有频道但全部没有台标的频道数。 */
    val channelsWithoutLogo: Int = 0,
    /** 内容看起来是 HTML 页面（目录页 / 错误页），不是直播源。 */
    val htmlPage: Boolean = false,
    /** 原始文本是否为空。 */
    val empty: Boolean = false,
    /** 是否为 M3U 结构（用于给用户说明"识别为 M3U/TXT"）。 */
    val format: String = "TXT",
) {
    val ok: Boolean get() = channelCount > 0 && !htmlPage && !empty

    /** 一句话结论，直接展示在导入确认弹窗/提示里。 */
    fun headline(): String = when {
        empty -> "内容为空，无法导入"
        htmlPage -> "内容是网页（目录页或错误页），不是直播源文件"
        channelCount == 0 -> "未解析出任何频道，请检查源格式"
        else -> "识别为 $format 源，共 $channelCount 个频道 / $lineCount 条线路 / $groupCount 个分组"
    }

    /** 警告项（不影响导入，但要提醒用户）。 */
    fun warnings(): List<String> = buildList {
        if (droppedLines > 0) add("$droppedLines 行缺少有效地址已被忽略")
        if (channelsWithoutGroup > 0) add("$channelsWithoutGroup 个频道没有分组信息，已归入默认分组")
        if (channelsWithoutLogo > 0) add("$channelsWithoutLogo 个频道没有台标，将显示首字占位")
    }
}

private const val DRY_RUN_DEFAULT_GROUP_TXT = "电视直播"
private const val DRY_RUN_DEFAULT_GROUP_M3U = "直播"

/**
 * 对即将导入的直播源文本做结构校验。
 *
 * @param raw 源文件正文（已解码的 String）。
 */
fun dryRunLiveSource(raw: String): LiveSourceDryRun {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
    if (normalized.isBlank()) return LiveSourceDryRun(empty = true)
    val isM3u = normalized.lineSequence().any {
        val t = it.trimStart()
        t.startsWith("#EXTINF", ignoreCase = true) || t.startsWith("#EXTM3U", ignoreCase = true)
    }
    val channels = parseLiveChannels(normalized)
    if (channels.isEmpty()) {
        val looksHtml = normalized.lineSequence().take(40).any {
            val t = it.trim().lowercase()
            t.startsWith("<html") || t.startsWith("<!doctype") || t.startsWith("<body")
        }
        return LiveSourceDryRun(
            htmlPage = looksHtml,
            format = if (isM3u) "M3U" else "TXT",
        )
    }
    // TXT 源的兜底分组名由解析器写成"电视直播"，M3U 写"直播"；这里只统计"没有显式分组"的频道。
    val fallbackGroup = if (isM3u) DRY_RUN_DEFAULT_GROUP_M3U else DRY_RUN_DEFAULT_GROUP_TXT
    val withoutGroup = channels.count { it.groupName.isBlank() || it.groupName == fallbackGroup }
    return LiveSourceDryRun(
        channelCount = channels.size,
        lineCount = channels.sumOf { it.lines.size },
        groupCount = channels.map { it.groupName }.filter { it.isNotBlank() }.distinct().size,
        droppedLines = countDroppedTxtLines(normalized, isM3u),
        channelsWithoutGroup = withoutGroup,
        channelsWithoutLogo = channels.count { it.logo.isBlank() },
        format = if (isM3u) "M3U" else "TXT",
    )
}

/**
 * 统计"看起来是频道行但没解析出地址"的行数：
 * TXT 里是含逗号但逗号后无可用地址；M3U 里是 #EXTINF 之后没有跟地址行。
 * 只做粗粒度计数，用于给用户提示"有多少行被忽略"，不参与解析结果。
 */
private fun countDroppedTxtLines(text: String, isM3u: Boolean): Int {
    if (isM3u) {
        var dropped = 0
        var pendingExtinf = false
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (pendingExtinf) dropped++
                    pendingExtinf = true
                }
                line.startsWith("#") -> Unit
                pendingExtinf -> pendingExtinf = false
                else -> Unit
            }
        }
        if (pendingExtinf) dropped++
        return dropped
    }
    return text.lineSequence().count { rawLine ->
        val line = rawLine.trim().replace('，', ',')
        if (line.isBlank() || line.startsWith("#")) return@count false
        val commaIndex = line.indexOf(',')
        // 逗号缺失是注释/说明行；逗号在末位则是"有名字没地址"，属于要统计的丢弃行。
        if (commaIndex <= 0) return@count false
        val sourcePart = line.substring(commaIndex + 1).trim()
        if (sourcePart.equals("#genre#", ignoreCase = true)) return@count false
        sourcePart.isBlank() || !(sourcePart.contains("://") || sourcePart.contains(".m3u8", true))
    }
}
