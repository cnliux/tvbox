package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第 312 轮 · 频道列表面板运行层单测
 * 锁定 [toggleChannelGroup]、[visibleChannelItemCount]、[nextChannelGroupIndex]、
 * [channelListHint]：折叠只留组头、方向键跳过折叠组、三种空态引导文案。
 */
class LiveChannelListRuntimeTest {

    private fun channel(name: String, group: String, number: Int = 1) = LiveChannel(
        number = number, groupName = group, name = name, lines = listOf(LiveChannelLine("默认", "u")),
    )

    private fun item(index: Int, ch: LiveChannel, current: Boolean = false) =
        ChannelListItem(index, ch, isCurrent = current)

    private fun snapshot(
        recent: List<ChannelListItem> = emptyList(),
        sections: List<ChannelGroupSection>,
    ) = ChannelListSnapshot(recent, sections)

    @Test
    fun toggleChannelGroupAddsAndRemoves() {
        val empty = emptySet<String>()
        assertEquals(setOf("A"), toggleChannelGroup(empty, "A"))
        assertEquals(emptySet<String>(), toggleChannelGroup(setOf("A"), "A"))
    }

    @Test
    fun visibleCountExcludesCollapsedItemsButKeepsRecent() {
        val recent = listOf(item(0, channel("r", "R")))
        val sections = listOf(
            ChannelGroupSection("A", collapsed = false, items = listOf(item(1, channel("a1", "A")), item(2, channel("a2", "A")))),
            ChannelGroupSection("B", collapsed = true, items = listOf(item(3, channel("b1", "B")))),
        )
        val snap = snapshot(recent, sections)
        // 最近 1 + A 组 2 + B 组折叠 0
        assertEquals(3, visibleChannelItemCount(snap))
    }

    @Test
    fun nextGroupIndexSkipsCollapsedAndWraps() {
        val sections = listOf(
            ChannelGroupSection("A", collapsed = true, items = emptyList()),
            ChannelGroupSection("B", collapsed = false, items = listOf(item(1, channel("b", "B")))),
            ChannelGroupSection("C", collapsed = false, items = listOf(item(2, channel("c", "C")))),
        )
        val snap = snapshot(sections = sections)
        // 从 B(1) 往下应跳到 C(2)，不会停在折叠的 A
        assertEquals(2, nextChannelGroupIndex(snap, fromIndex = 1, direction = 1))
        // 从 C(2) 往下回绕到 B(1)（跳过 A）
        assertEquals(1, nextChannelGroupIndex(snap, fromIndex = 2, direction = 1))
    }

    @Test
    fun nextGroupIndexNullWhenAllCollapsed() {
        val sections = listOf(ChannelGroupSection("A", collapsed = true, items = emptyList()))
        assertNull(nextChannelGroupIndex(snapshot(sections = sections), fromIndex = 0, direction = 1))
    }

    @Test
    fun channelListHintCoversEmptyStates() {
        // query 无匹配时快照已被过滤为空条目（不是整组消失）
        val noMatch = snapshot(
            sections = listOf(ChannelGroupSection("A", false, items = emptyList())),
        )
        assertEquals(
            "没有匹配「xyz」的频道，可清空搜索或按数字键选台",
            channelListHint(noMatch, "xyz"),
        )
        assertEquals("暂无频道，请在设置页导入直播源", channelListHint(snapshot(sections = emptyList()), ""))
        val allCollapsed = snapshot(sections = listOf(ChannelGroupSection("A", true, emptyList())))
        assertEquals("所有分组已折叠，按确认键展开", channelListHint(allCollapsed, ""))
        assertEquals("", channelListHint(snapshot(sections = listOf(ChannelGroupSection("A", false, listOf(item(1, channel("a", "A")))))), ""))
    }
}
