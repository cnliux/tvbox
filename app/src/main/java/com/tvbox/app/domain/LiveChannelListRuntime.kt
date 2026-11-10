package com.tvbox.app.domain

/**
 * 频道列表面板的运行层（第 271–280 轮的领域部分）。
 *
 * `buildChannelListSnapshot` 负责生成可渲染快照；这里补遥控器在分组间的
 * 折叠切换、可见条目计数与空态提示，避免面板里再散落一套下标运算。
 */

fun toggleChannelGroup(collapsedGroups: Set<String>, groupName: String): Set<String> =
    if (groupName in collapsedGroups) collapsedGroups - groupName else collapsedGroups + groupName

/** 折叠的组只显示组头；最近观看置顶区与分组区都要计入可见条目。 */
fun visibleChannelItemCount(snapshot: ChannelListSnapshot): Int =
    snapshot.recent.size + snapshot.sections.sumOf { section -> if (section.collapsed) 0 else section.items.size }

/** 方向键在组头之间移动时跳过没有条目的折叠组，返回下一组下标；无可选组时返回 null。 */
fun nextChannelGroupIndex(
    snapshot: ChannelListSnapshot,
    fromIndex: Int,
    direction: Int,
): Int? {
    val sections = snapshot.sections
    if (sections.isEmpty()) return null
    val step = if (direction >= 0) 1 else -1
    var cursor = (fromIndex + step + sections.size) % sections.size
    repeat(sections.size) {
        if (!sections[cursor].collapsed) return cursor
        cursor = (cursor + step + sections.size) % sections.size
    }
    return null
}

/** 搜索无结果 / 全部折叠 / 无频道三种情况的引导文案。 */
fun channelListHint(snapshot: ChannelListSnapshot, query: String): String = when {
    query.isNotBlank() && visibleChannelItemCount(snapshot) == 0 -> "没有匹配「$query」的频道，可清空搜索或按数字键选台"
    snapshot.sections.isEmpty() -> "暂无频道，请在设置页导入直播源"
    snapshot.sections.all { it.collapsed } -> "所有分组已折叠，按确认键展开"
    else -> ""
}
