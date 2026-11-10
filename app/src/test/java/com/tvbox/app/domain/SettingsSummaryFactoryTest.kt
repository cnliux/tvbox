package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSummaryFactoryTest {

    @Test
    fun defaultSettingsProduceAllFiveGroups() {
        val summary = buildSettingsPageSummary(settingsEntriesFrom(AppSettings()))
        assertEquals(5, summary.groups.size)
        assertEquals(0, summary.emptyGroups.size)
        assertEquals(12, summary.totalEntries)
    }

    @Test
    fun everyEntryHasAReadableValueSummary() {
        val entries = settingsEntriesFrom(AppSettings())
        assertTrue(entries.all { it.valueSummary.isNotBlank() })
        assertTrue(entries.all { settingEntrySummary(it) != "未设置" || it.id == "epg" })
        assertTrue(settingRowLabel(entries.first()).startsWith("默认倍速："))
    }

    @Test
    fun configuredSourcesAreReflectedInSummary() {
        val entries = settingsEntriesFrom(
            AppSettings(
                homeApiLineId = "主源",
                customVideoApiLines = listOf(CustomVideoApiLine("c1", "自定义", "https://a.test/")),
                liveSourceUrls = listOf("https://live.test/a.m3u", "https://live.test/b.m3u"),
                blockedVideoCategories = listOf("伦理片"),
            ),
        )
        assertEquals("主源（1 个自定义）", entries.first { it.id == "home_source" }.valueSummary)
        assertEquals("2 个订阅", entries.first { it.id == "live_sources" }.valueSummary)
        assertTrue(entries.first { it.id == "blocked" }.valueSummary.contains("1 个分类"))
    }

    @Test
    fun skipCreditsSummaryReflectsPersistedSeconds() {
        val off = settingsEntriesFrom(AppSettings()).first { it.id == "skip_credits" }
        assertEquals("跳过片头/片尾：关闭", off.valueSummary)
        val on = settingsEntriesFrom(AppSettings(introSkipSeconds = 30, outroSkipSeconds = 20))
            .first { it.id == "skip_credits" }
        assertEquals("已开启 · 片头 30 秒 · 片尾 20 秒", on.valueSummary)
    }

    @Test
    fun epgShowsUnsetWhenBlank() {
        val entry = settingsEntriesFrom(AppSettings()).first { it.id == "epg" }
        assertEquals("未设置", settingEntrySummary(entry))
        assertEquals("未设置", entry.valueSummary)
        assertFalse(settingRowLabel(entry).contains("：："))
    }
}
