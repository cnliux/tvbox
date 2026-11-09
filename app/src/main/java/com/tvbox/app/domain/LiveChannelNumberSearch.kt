package com.tvbox.app.domain

/**
 * 数字选台的前缀匹配（遥控器输入中实时反馈）。
 *
 * 规则：
 * - 输入为空时返回空列表（调用方视为"未在选择"）。
 * - 以输入串为前缀匹配频道号，保持源内原有顺序。
 * - 同号频道（多个源合并时可能出现）全部保留，由 UI 按行号区分。
 * - 结果最多 [limit] 条，避免输入 "1" 时一次性铺几百行。
 */
data class LiveChannelNumberMatch(
    /** 频道在完整频道列表中的下标（用于切台）。 */
    val index: Int,
    val number: Int,
    val channel: LiveChannel,
)

internal const val LIVE_NUMBER_SEARCH_LIMIT = 50

fun findLiveChannelsByNumberPrefix(
    channels: List<LiveChannel>,
    input: String,
    limit: Int = LIVE_NUMBER_SEARCH_LIMIT,
): List<LiveChannelNumberMatch> {
    val prefix = input.trim()
    if (prefix.isEmpty() || prefix.any { !it.isDigit() }) return emptyList()
    if (limit <= 0) return emptyList()
    val result = ArrayList<LiveChannelNumberMatch>(minOf(limit, 16))
    for ((index, channel) in channels.withIndex()) {
        if (channel.number.toString().startsWith(prefix)) {
            result += LiveChannelNumberMatch(index = index, number = channel.number, channel = channel)
            if (result.size >= limit) break
        }
    }
    return result
}

/**
 * 数字输入自动提交时决定跳到哪一台：
 * 1. 精确等于输入号码的频道优先（输入完整号码即选中，符合直觉）；
 * 2. 否则取前缀匹配的第一条（用户可以只输 "0" 就进到 0 号台）。
 * 返回频道下标，无匹配返回 -1。
 */
fun resolveLiveChannelNumberTarget(
    channels: List<LiveChannel>,
    input: String,
): Int {
    val trimmed = input.trim()
    // 用字符串比较做精确匹配：数字串长度可能超 Int 范围，toIntOrNull() 返回 null 时
    // 旧实现直接 return -1，会跳过下面的前缀匹配兜底（输入 "999999999999" 即失效）。
    val exactIndex = channels.indexOfFirst { it.number.toString() == trimmed }
    if (exactIndex >= 0) return exactIndex
    return findLiveChannelsByNumberPrefix(channels, trimmed, limit = 1).firstOrNull()?.index ?: -1
}
