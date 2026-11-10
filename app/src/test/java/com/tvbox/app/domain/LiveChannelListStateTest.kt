package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelListStateTest {
    private fun channel(number: Int, group: String, name: String) = LiveChannel(
        number = number,
        groupName = group,
        name = name,
        lines = listOf(LiveChannelLine(name = "线路1", url = "https://cdn.test/$number.m3u8")),
    )

    private val channels = listOf(
        channel(1, "央视", "综合"),
        channel(2, "卫视", "湖南卫视"),
        channel(3, "央视", "新闻"),
        channel(4, "卫视", "浙江卫视"),
    )

    @Test
    fun groupsKeepFirstAppearanceOrder() {
        val snapshot = buildChannelListSnapshot(channels)
        assertEquals(listOf("央视", "卫视"), snapshot.sections.map { it.groupName })
        assertEquals(listOf(0, 2), snapshot.sections.first().items.map { it.index })
        assertTrue(snapshot.recent.isEmpty())
    }

    @Test
    fun collapsedGroupKeepsHeaderButDropsItems() {
        val snapshot = buildChannelListSnapshot(channels, collapsedGroups = setOf("央视"))
        val section = snapshot.sections.first()
        assertEquals("央视", section.groupName)
        assertTrue(section.collapsed)
        assertTrue(section.items.isEmpty())
        assertEquals(2, snapshot.sections[1].items.size)
    }

    @Test
    fun queryFiltersByChannelNameAndGroupName() {
        val byName = buildChannelListSnapshot(channels, query = "新闻")
        assertEquals(listOf(2), byName.sections.flatMap { it.items }.map { it.index })

        val byGroup = buildChannelListSnapshot(channels, query = "卫视")
        assertEquals(listOf(1, 3), byGroup.sections.flatMap { it.items }.map { it.index })
    }

    @Test
    fun recentChannelsArePinnedInGivenOrderAndRespectLimit() {
        val snapshot = buildChannelListSnapshot(
            channels = channels,
            recentKeys = listOf("卫视/浙江卫视", "央视/综合"),
            recentLimit = 1,
        )
        assertEquals(1, snapshot.recent.size)
        assertEquals(3, snapshot.recent.first().index)
        assertTrue(snapshot.recent.first().isRecent)
    }

    @Test
    fun currentChannelIsHighlightedInBothRecentAndSections() {
        val snapshot = buildChannelListSnapshot(
            channels = channels,
            recentKeys = listOf("央视/新闻"),
            currentIndex = 2,
        )
        assertTrue(snapshot.recent.single().isCurrent)
        assertTrue(snapshot.sections.first().items.last().isCurrent)
        assertFalse(snapshot.sections.first().items.first().isCurrent)
    }

    @Test
    fun stableKeyUsesGroupAndName() {
        assertEquals("央视/综合", liveChannelStableKey(channels.first()))
    }
}
