package com.tvbox.app.domain

/**
 * 频道列表的分组 / 折叠 / 搜索 / 最近观看状态（第 93 轮）。
 *
 * 与 [findLiveChannelsByNumberPrefix] 互补：那个只处理遥控器数字选台，
 * 这里为左侧频道面板生成可直接渲染的快照，把「分组折叠、文字过滤、最近观看置顶、
 * 当前频道高亮」收敛成纯函数，UI 只负责渲染与转发点击事件。
 */
data class ChannelListItem(
    val index: Int,
    val channel: LiveChannel,
    val isRecent: Boolean = false,
    val isCurrent: Boolean = false,
)

data class ChannelGroupSection(
    val groupName: String,
    val collapsed: Boolean,
    /** 折叠时为空，但组头仍保留，方便遥控器展开。 */
    val items: List<ChannelListItem>,
)

data class ChannelListSnapshot(
    val recent: List<ChannelListItem>,
    val sections: List<ChannelGroupSection>,
)

/** 与 `LiveLinePreference.LiveChannelKey` 一致的稳定键，domain 层不依赖 data 实现。 */
fun liveChannelStableKey(channel: LiveChannel): String = "${channel.groupName}/${channel.name}"

fun buildChannelListSnapshot(
    channels: List<LiveChannel>,
    collapsedGroups: Set<String> = emptySet(),
    recentKeys: List<String> = emptyList(),
    query: String = "",
    currentIndex: Int = -1,
    recentLimit: Int = 6,
): ChannelListSnapshot {
    val normalizedQuery = query.trim()
    val filtered = channels.withIndex().filter { (_, channel) ->
        normalizedQuery.isEmpty() ||
            channel.name.contains(normalizedQuery, ignoreCase = true) ||
            channel.groupName.contains(normalizedQuery, ignoreCase = true)
    }

    val byKey = filtered.associateBy { (_, channel) -> liveChannelStableKey(channel) }
    val recent = recentKeys
        .mapNotNull { key -> byKey[key] }
        .take(recentLimit.coerceAtLeast(0))
        .map { (index, channel) ->
            ChannelListItem(index, channel, isRecent = true, isCurrent = index == currentIndex)
        }

    // LinkedHashMap 保持分组在源文件中首次出现的顺序；折叠组仍输出组头。
    val grouped = LinkedHashMap<String, MutableList<IndexedValue<LiveChannel>>>()
    filtered.forEach { indexed ->
        grouped.getOrPut(indexed.value.groupName) { mutableListOf() }.add(indexed)
    }
    val sections = grouped.map { (groupName, items) ->
        val collapsed = groupName in collapsedGroups
        ChannelGroupSection(
            groupName = groupName,
            collapsed = collapsed,
            items = if (collapsed) {
                emptyList()
            } else {
                items.map { (index, channel) ->
                    ChannelListItem(index, channel, isCurrent = index == currentIndex)
                }
            },
        )
    }
    return ChannelListSnapshot(recent = recent, sections = sections)
}
