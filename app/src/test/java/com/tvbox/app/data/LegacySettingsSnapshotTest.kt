package com.tvbox.app.data

import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.VideoScaleMode
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySettingsSnapshotTest {

    private fun snapshotOf(vararg entries: Pair<String, Any?>): LegacySettingsSnapshot =
        LegacySettingsSnapshot(values = entries.toMap())

    @Test
    fun `空快照返回全部默认值`() {
        val settings = readLegacySettings(LegacySettingsSnapshot(emptyMap()))
        assertEquals(com.tvbox.app.domain.AppSettings(), settings)
    }

    @Test
    fun `schema 版本从 1 起`() {
        assertEquals(1, SettingsSchema.CURRENT)
        assertEquals("schema_version", SettingsStorageKeys.SCHEMA_VERSION)
    }

    @Test
    fun `全量键值按原类型解析`() {
        val customLines = legacySettingsJson.encodeToString(
            listOf(com.tvbox.app.domain.CustomVideoApiLine(id = "a", name = "A", baseUrl = "https://a")),
        )
        val settings = readLegacySettings(
            snapshotOf(
                SettingsStorageKeys.HOME_API_LINE_ID to "line-1",
                SettingsStorageKeys.CUSTOM_VIDEO_API_LINES to customLines,
                SettingsStorageKeys.SOURCE_ORDER to legacySettingsJson.encodeToString(listOf("x", "y")),
                SettingsStorageKeys.BLOCKED_SOURCE_IDS to legacySettingsJson.encodeToString(listOf("bad")),
                SettingsStorageKeys.LIVE_SOURCE_URLS to legacySettingsJson.encodeToString(listOf("https://live")),
                SettingsStorageKeys.BLOCKED_PLATFORM_SITE_IDS to legacySettingsJson.encodeToString(listOf("douyu")),
                SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES to legacySettingsJson.encodeToString(listOf("电影")),
                SettingsStorageKeys.INTRO_SKIP_SECONDS to 15,
                SettingsStorageKeys.OUTRO_SKIP_SECONDS to 30,
                SettingsStorageKeys.HOME_CONTINUE_WATCHING to true,
                SettingsStorageKeys.THEME to TvTheme.Cinema.storageKey,
                SettingsStorageKeys.FONT_SCALE to TvFontScale.ExtraLarge.storageKey,
                SettingsStorageKeys.CHECK_UPDATES_ON_STARTUP to false,
                SettingsStorageKeys.PLAYBACK_AGENT_AUTO_SWITCH to false,
                SettingsStorageKeys.VIDEO_SCALE_MODE to VideoScaleMode.Zoom.storageKey,
                SettingsStorageKeys.DEVICE_MODE to DeviceMode.Mobile.storageKey,
                SettingsStorageKeys.LIVE_SOURCE_DEFAULT_GROUP to "央视",
                SettingsStorageKeys.MOBILE_ONBOARDED to true,
                SettingsStorageKeys.LIVE_SOURCE_DEFAULT_INDEX to 2,
                SettingsStorageKeys.EPISODE_SORT_REVERSE to true,
                SettingsStorageKeys.VIDEO_GRID_COLUMNS to 5,
                SettingsStorageKeys.EPG_URL to "https://epg",
                SettingsStorageKeys.PLATFORM_LIVE_GRID_COLUMNS to 4,
                SettingsStorageKeys.CONTINUE_WATCHING_COUNT to 8,
                SettingsStorageKeys.EPG_CARD_ROWS to 3,
                SettingsStorageKeys.LIVE_NUMBER_MATCH_ROWS to 8,
                SettingsStorageKeys.LIVE_NUMBER_SEARCH_LIMIT to 50,
                SettingsStorageKeys.RELATED_CHANNEL_COUNT to 16,
                SettingsStorageKeys.HOT_WORD_COUNT to 21,
                SettingsStorageKeys.SEARCH_SUGGESTION_COUNT to 4,
                SettingsStorageKeys.HOME_HOT_CONTENT_COUNT to 30,
                SettingsStorageKeys.DOUBAN_HOT_PAGE_SIZE to 25,
                SettingsStorageKeys.DOUBAN_HOT_FALLBACK_LINES to 8,
                SettingsStorageKeys.HISTORY_MAX_ITEMS to 300,
                SettingsStorageKeys.MAX_PARALLEL_SOURCE_REQUESTS to 6,
            ),
        )
        assertEquals("line-1", settings.homeApiLineId)
        assertEquals(1, settings.customVideoApiLines.size)
        assertEquals(listOf("x", "y"), settings.sourceOrder)
        assertEquals(listOf("bad"), settings.blockedSourceIds)
        assertEquals(listOf("https://live"), settings.liveSourceUrls)
        assertEquals(listOf("douyu"), settings.blockedPlatformSiteIds)
        assertEquals(listOf("电影"), settings.blockedVideoCategories)
        assertEquals(15, settings.introSkipSeconds)
        assertEquals(30, settings.outroSkipSeconds)
        assertTrue(settings.homeContinueWatchingEnabled)
        assertEquals(TvTheme.Cinema, settings.theme)
        assertEquals(TvFontScale.ExtraLarge, settings.fontScale)
        assertEquals(false, settings.checkUpdatesOnStartup)
        assertEquals(false, settings.playbackAgentAutoSwitchEnabled)
        assertEquals(VideoScaleMode.Zoom, settings.videoScaleMode)
        assertEquals(DeviceMode.Mobile, settings.deviceMode)
        assertEquals("央视", settings.liveSourceDefaultGroup)
        assertTrue(settings.mobileOnboarded)
        assertEquals(2, settings.liveSourceDefaultIndex)
        assertTrue(settings.episodeSortReverse)
        assertEquals(5, settings.videoGridColumns)
        assertEquals("https://epg", settings.epgUrl)
        assertEquals(4, settings.platformLiveGridColumns)
        assertEquals(8, settings.continueWatchingCount)
        assertEquals(3, settings.epgCardRows)
        assertEquals(8, settings.liveNumberMatchRows)
        assertEquals(50, settings.liveNumberSearchLimit)
        assertEquals(16, settings.relatedChannelCount)
        assertEquals(21, settings.hotWordCount)
        assertEquals(4, settings.searchSuggestionCount)
        assertEquals(30, settings.homeHotContentCount)
        assertEquals(25, settings.doubanHotPageSize)
        assertEquals(8, settings.doubanHotFallbackLines)
        assertEquals(300, settings.historyMaxItems)
        assertEquals(6, settings.maxParallelSourceRequests)
    }

    @Test
    fun `损坏的 JSON 列表回退为空且不抛异常`() {
        val warnings = mutableListOf<String>()
        val settings = readLegacySettings(
            snapshotOf(
                SettingsStorageKeys.SOURCE_ORDER to "{not-json",
                SettingsStorageKeys.CUSTOM_VIDEO_API_LINES to "[\"broken\"",
                SettingsStorageKeys.LIVE_SOURCE_URLS to "42",
            ),
            onWarning = { message, _ -> warnings += message },
        )
        assertEquals(emptyList<String>(), settings.sourceOrder)
        assertEquals(emptyList<com.tvbox.app.domain.CustomVideoApiLine>(), settings.customVideoApiLines)
        assertEquals(emptyList<String>(), settings.liveSourceUrls)
        assertEquals(3, warnings.size)
    }

    @Test
    fun `类型不符按缺失处理`() {
        val settings = readLegacySettings(
            snapshotOf(
                SettingsStorageKeys.HOME_API_LINE_ID to 123,
                SettingsStorageKeys.HOME_CONTINUE_WATCHING to "true",
                SettingsStorageKeys.INTRO_SKIP_SECONDS to "15",
            ),
        )
        assertEquals("", settings.homeApiLineId)
        assertEquals(false, settings.homeContinueWatchingEnabled)
        assertEquals(0, settings.introSkipSeconds)
    }

    @Test
    fun `非法枚举回退默认值`() {
        val settings = readLegacySettings(
            snapshotOf(
                SettingsStorageKeys.THEME to "no-such-theme",
                SettingsStorageKeys.FONT_SCALE to "huge",
                SettingsStorageKeys.VIDEO_SCALE_MODE to "stretch",
                SettingsStorageKeys.DEVICE_MODE to "tablet",
            ),
        )
        assertEquals(TvTheme.Default, settings.theme)
        assertEquals(TvFontScale.Normal, settings.fontScale)
        assertEquals(VideoScaleMode.Fit, settings.videoScaleMode)
        assertEquals(DeviceMode.Auto, settings.deviceMode)
    }

    @Test
    fun `数量设置越界收敛到区间`() {
        val settings = readLegacySettings(
            snapshotOf(
                SettingsStorageKeys.HISTORY_MAX_ITEMS to 9999,
                SettingsStorageKeys.CONTINUE_WATCHING_COUNT to 0,
                SettingsStorageKeys.MAX_PARALLEL_SOURCE_REQUESTS to -3,
            ),
        )
        assertEquals(QuantitySettings.HistoryMaxItems.max, settings.historyMaxItems)
        assertEquals(QuantitySettings.ContinueWatchingCount.min, settings.continueWatchingCount)
        assertEquals(QuantitySettings.MaxParallelSourceRequests.min, settings.maxParallelSourceRequests)
    }

    @Test
    fun `屏蔽分类优先使用独立文件`() {
        val settings = readLegacySettings(
            LegacySettingsSnapshot(
                values = mapOf(
                    SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES to
                        legacySettingsJson.encodeToString(listOf("旧偏好")),
                ),
                blockedCategoriesFromFile = listOf("文件分类"),
            ),
        )
        assertEquals(listOf("文件分类"), settings.blockedVideoCategories)
    }

    @Test
    fun `独立文件缺失时回退偏好里的屏蔽分类`() {
        val settings = readLegacySettings(
            LegacySettingsSnapshot(
                values = mapOf(
                    SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES to
                        legacySettingsJson.encodeToString(listOf("旧偏好")),
                ),
                blockedCategoriesFromFile = null,
            ),
        )
        assertEquals(listOf("旧偏好"), settings.blockedVideoCategories)
    }

    @Test
    fun `测速延迟把负值还原为 null`() {
        val map = decodeLegacySpeedLatencies(
            legacySettingsJson.encodeToString(mapOf("a" to 120L, "b" to -1L)),
        )
        assertEquals(120L, map["a"])
        assertNull(map["b"])
    }

    @Test
    fun `测速延迟缺失或损坏时为空`() {
        assertTrue(decodeLegacySpeedLatencies(null).isEmpty())
        assertTrue(decodeLegacySpeedLatencies("").isEmpty())
        assertTrue(decodeLegacySpeedLatencies("oops").isEmpty())
    }
}
