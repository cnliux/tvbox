package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置摘要 / 频道列表运行层 / OTA CDN 状态行三组纯函数的回归。 */
class RuntimeStateTest {

    // ------------------------------------------------------------ 设置页

    @Test
    fun settingsSummaryCountsEntriesAndEmptyGroups() {
        val entries = listOf(
            SettingEntry("speed", SettingGroup.Playback, "默认倍速", "1.0x"),
            SettingEntry("scale", SettingGroup.Playback, "画面比例"),
            SettingEntry("theme", SettingGroup.Appearance, "主题", "影院主题"),
        )
        val summary = buildSettingsPageSummary(entries)
        assertEquals(3, summary.totalEntries)
        assertEquals(5, summary.groups.size)
        assertEquals(3, summary.emptyGroups.size)
        assertEquals("默认倍速：1.0x", settingRowLabel(entries[0]))
        assertEquals("画面比例：未设置", settingRowLabel(entries[1]))
    }

    @Test
    fun groupSummaryReportsMissingValues() {
        val state = buildSettingGroups(
            listOf(
                SettingEntry("a", SettingGroup.Playback, "A", "1"),
                SettingEntry("b", SettingGroup.Playback, "B"),
            ),
        ).first()
        assertEquals("2 项 · 1 项未设置", settingGroupSummary(state))
    }

    @Test
    fun dangerousActionRequiresConfirmation() {
        assertNotNull(pendingDangerousConfirmation(DangerousAction.ClearSources, false))
        assertNull(pendingDangerousConfirmation(DangerousAction.ClearHistory, true))
        assertTrue(requiresSecondConfirmation(DangerousAction.ClearSources))
        assertTrue(dangerousActionConfirmation(DangerousAction.ClearHistory).contains("无法恢复"))
    }

    // ------------------------------------------------------------ 频道列表

    private fun channels() = listOf(
        LiveChannel(1, "央视", "CCTV-1", listOf(LiveChannelLine("主", "https://a.test/1.m3u8"))),
        LiveChannel(2, "卫视", "湖南卫视", listOf(LiveChannelLine("主", "https://a.test/2.m3u8"))),
    )

    @Test
    fun toggleGroupIsSymmetric() {
        val collapsed = toggleChannelGroup(emptySet(), "央视")
        assertEquals(setOf("央视"), collapsed)
        assertEquals(emptySet<String>(), toggleChannelGroup(collapsed, "央视"))
    }

    @Test
    fun visibleCountExcludesCollapsedSections() {
        val snapshot = buildChannelListSnapshot(channels(), recentKeys = listOf("央视/CCTV-1"))
        assertEquals(3, visibleChannelItemCount(snapshot))
        val collapsed = buildChannelListSnapshot(
            channels(), collapsedGroups = setOf("央视", "卫视"),
        )
        assertEquals(0, visibleChannelItemCount(collapsed))
    }

    @Test
    fun nextGroupSkipsCollapsedSectionsAndReturnsNullWhenAllCollapsed() {
        val snapshot = buildChannelListSnapshot(channels(), collapsedGroups = setOf("央视"))
        assertEquals(1, nextChannelGroupIndex(snapshot, fromIndex = 0, direction = 1))
        val allCollapsed = buildChannelListSnapshot(
            channels(), collapsedGroups = setOf("央视", "卫视"),
        )
        assertNull(nextChannelGroupIndex(allCollapsed, fromIndex = 0, direction = 1))
    }

    @Test
    fun channelListHintCoversQueryAndEmptySource() {
        assertTrue(channelListHint(buildChannelListSnapshot(channels(), query = "不存在的台"), "不存在的台").contains("没有匹配"))
        assertTrue(channelListHint(buildChannelListSnapshot(emptyList()), "").contains("导入直播源"))
        assertTrue(
            channelListHint(
                buildChannelListSnapshot(channels(), collapsedGroups = setOf("央视", "卫视")),
                "",
            ).contains("已折叠"),
        )
    }

    // ------------------------------------------------------------ OTA CDN

    private val candidates = listOf(
        OtaCdnCandidate("origin", "官方源", "https://github.com/a/b.apk"),
        OtaCdnCandidate("mirror", "镜像", "https://mirror.test/b.apk"),
    )

    @Test
    fun manualPreferenceMovesChosenLineToFrontAndMarksIt() {
        val rows = buildCdnStatusRows(
            candidates,
            health = emptyMap(),
            preference = CdnPreference(CdnSelectionMode.Manual, "mirror"),
            nowMs = 1_000L,
        )
        assertEquals("mirror", rows.first().id)
        assertTrue(rows.first().selected)
        assertFalse(rows[1].selected)
        assertEquals("未使用", rows[0].status)
    }

    @Test
    fun autoPreferenceKeepsDeclaredOrderAndNoSelection() {
        val rows = buildCdnStatusRows(candidates, emptyMap(), nowMs = 0L)
        assertEquals(listOf("origin", "mirror"), rows.map { it.id })
        assertTrue(rows.none { it.selected })
    }

    @Test
    fun statusRowShowsFailureCountWithoutUrl() {
        val rows = buildCdnStatusRows(
            candidates,
            mapOf("origin" to OtaCdnHealth(consecutiveFailures = 2)),
            nowMs = 0L,
        )
        assertEquals("连续失败 2 次", rows.first().status)
        assertTrue(rows.none { it.status.contains("https://") })
    }

    @Test
    fun probeSummaryAndCoverageDoNotLeakUrls() {
        assertEquals("尚未探活", summarizeCdnProbeResults(emptyMap()))
        assertEquals("探活完成：2/3 条线路可用", summarizeCdnProbeResults(mapOf("a" to true, "b" to false, "c" to true)))
        val plan = buildCdnProbePlan(candidates, budgetMs = 3_000L)
        assertEquals(2, plan.size)
        assertEquals(50, cdnProbeCoverage(listOf("a" to 1L, "b" to 1L), mapOf("a" to true)))
        assertEquals(100, cdnProbeCoverage(plan, mapOf("origin" to true, "mirror" to false)))
    }
}
