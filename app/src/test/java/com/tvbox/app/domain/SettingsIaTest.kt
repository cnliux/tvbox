package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIaTest {

    private val entries = listOf(
        SettingEntry("api", SettingGroup.ContentSources, "视频接口", "XX（3 个）"),
        SettingEntry("theme", SettingGroup.Appearance, "主题"),
        SettingEntry("skip", SettingGroup.Playback, "跳过片头片尾", "关闭"),
    )

    @Test
    fun fiveGroupsKeepDeclaredOrder() {
        val groups = buildSettingGroups(entries)
        assertEquals(SettingGroup.entries.toList(), groups.map { it.group })
        assertEquals(1, groups.first { it.group == SettingGroup.ContentSources }.entries.size)
        assertEquals("1 项", groups.first { it.group == SettingGroup.Appearance }.summary)
    }

    @Test
    fun blankValueSummaryShowsExplicitUnset() {
        assertEquals("未设置", settingEntrySummary(entries[1]))
        assertEquals("XX（3 个）", settingEntrySummary(entries[0]))
    }

    @Test
    fun dangerousActionsRequireExplicitIrreversibleConfirmation() {
        DangerousAction.entries.forEach { action ->
            assertTrue(requiresSecondConfirmation(action))
            assertTrue(dangerousActionConfirmation(action).contains("无法"))
        }
        assertEquals("清空源配置", dangerousActionLabel(DangerousAction.ClearSources))
    }

    @Test
    fun tvFocusPathIsPredictableAndReturnsToCategoryList() {
        val groups = buildSettingGroups(entries)
        val path = tvFocusPath(groups, startIndex = 2)
        assertEquals("分类:外观", path.first())
        assertEquals("条目:主题", path[1])
        assertEquals("返回:分类列表", path.last())
        assertEquals("分类:播放与画面", tvFocusPath(groups, startIndex = -5).first())
        assertEquals("分类:高级", tvFocusPath(groups, startIndex = 99).first())
    }
}
