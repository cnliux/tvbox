package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantitySettingsTest {
    private val settings = listOf(
        QuantitySettings.ContinueWatchingCount,
        QuantitySettings.EpgCardRows,
        QuantitySettings.LiveNumberMatchRows,
        QuantitySettings.LiveNumberSearchLimit,
        QuantitySettings.RelatedChannelCount,
        QuantitySettings.HotWordCount,
        QuantitySettings.SearchSuggestionCount,
        QuantitySettings.HomeHotContentCount,
        QuantitySettings.DoubanHotPageSize,
        QuantitySettings.DoubanHotFallbackLines,
        QuantitySettings.HistoryMaxItems,
        QuantitySettings.MaxParallelSourceRequests,
    )

    @Test
    fun `默认值按大屏体验调优后的既定档位`() {
        assertEquals(8, QuantitySettings.ContinueWatchingCount.default)
        assertEquals(5, QuantitySettings.EpgCardRows.default)
        assertEquals(8, QuantitySettings.LiveNumberMatchRows.default)
        assertEquals(100, QuantitySettings.LiveNumberSearchLimit.default)
        assertEquals(16, QuantitySettings.RelatedChannelCount.default)
        assertEquals(24, QuantitySettings.HotWordCount.default)
        assertEquals(6, QuantitySettings.SearchSuggestionCount.default)
        // 首页热播首屏 30 条；滑到底会继续自动加载，该值不再充当硬上限。
        assertEquals(30, QuantitySettings.HomeHotContentCount.default)
        assertEquals(30, QuantitySettings.DoubanHotPageSize.default)
        assertEquals(8, QuantitySettings.DoubanHotFallbackLines.default)
        assertEquals(200, QuantitySettings.HistoryMaxItems.default)
        // 多源搜索并发默认拉满到上限 12，修复「多个源只搜到 1~2 个内容」。
        assertEquals(12, QuantitySettings.MaxParallelSourceRequests.default)
        assertEquals(
            QuantitySettings.MaxParallelSourceRequests.max,
            QuantitySettings.MaxParallelSourceRequests.default,
        )
    }

    @Test
    fun `AppSettings 默认值与数量设置一致`() {
        val defaults = AppSettings()
        assertEquals(QuantitySettings.ContinueWatchingCount.default, defaults.continueWatchingCount)
        assertEquals(QuantitySettings.EpgCardRows.default, defaults.epgCardRows)
        assertEquals(QuantitySettings.LiveNumberMatchRows.default, defaults.liveNumberMatchRows)
        assertEquals(QuantitySettings.LiveNumberSearchLimit.default, defaults.liveNumberSearchLimit)
        assertEquals(QuantitySettings.RelatedChannelCount.default, defaults.relatedChannelCount)
        assertEquals(QuantitySettings.HotWordCount.default, defaults.hotWordCount)
        assertEquals(QuantitySettings.SearchSuggestionCount.default, defaults.searchSuggestionCount)
        assertEquals(QuantitySettings.HomeHotContentCount.default, defaults.homeHotContentCount)
        assertEquals(QuantitySettings.DoubanHotPageSize.default, defaults.doubanHotPageSize)
        assertEquals(QuantitySettings.DoubanHotFallbackLines.default, defaults.doubanHotFallbackLines)
        assertEquals(QuantitySettings.HistoryMaxItems.default, defaults.historyMaxItems)
        assertEquals(QuantitySettings.MaxParallelSourceRequests.default, defaults.maxParallelSourceRequests)
    }

    @Test
    fun `可选档位在合法区间内且包含默认值`() {
        settings.forEach { setting ->
            assertTrue(setting.choices.contains(setting.default))
            assertTrue(setting.choices.all { it in setting.min..setting.max })
        }
    }

    @Test
    fun `越界值收敛到合法区间`() {
        settings.forEach { setting ->
            assertEquals(setting.max, setting.clamp(setting.max + 100))
            assertEquals(setting.min, setting.clamp(setting.min - 100))
            assertEquals(setting.default, setting.clamp(setting.default))
        }
    }
}
