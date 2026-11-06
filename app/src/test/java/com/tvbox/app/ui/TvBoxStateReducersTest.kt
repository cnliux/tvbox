package com.tvbox.app.ui

import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.Category
import com.tvbox.app.domain.EpgProgram
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBoxStateReducersTest {

    /** 并发回归：先返回的新源结果已写入后，迟到的旧源响应不得覆盖它。 */
    @Test
    fun staleCategoryResponseDoesNotOverwriteNewSource() {
        // 新源请求先完成。
        val afterNew = TvBoxUiState(selectedApiLineId = "line-new")
            .applyCategoryResult("line-new", listOf(Category(1, 0, "新源分类")))
        // 旧源请求晚到：此时当前选择已是 line-new。
        val afterStale = afterNew.applyCategoryResult("line-old", listOf(Category(9, 0, "旧源分类")))
        assertEquals(listOf("新源分类"), afterStale.categories.map { it.name })
    }

    @Test
    fun currentCategoryResponseIsAppliedWithBlockedFilter() {
        val state = TvBoxUiState(
            selectedApiLineId = "line-1",
            appSettings = AppSettings(blockedVideoCategories = listOf("电影")),
        )
        val result = state.applyCategoryResult(
            requestedApiLineId = "line-1",
            categories = listOf(Category(13, 0, "电影"), Category(14, 0, "连续剧")),
        )
        assertEquals(listOf("连续剧"), result.categories.map { it.name })
    }

    /** 并发回归：EPG 地址在请求期间被改掉，旧地址的节目单不得写回。 */
    @Test
    fun staleEpgResponseIsIgnoredAfterUrlChanged() {
        val state = TvBoxUiState(appSettings = AppSettings(epgUrl = "https://new.example/epg.xml"))
        val programs = mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L)))
        val result = state.applyEpgResult("https://old.example/epg.xml", programs)
        assertEquals(emptyMap<String, List<EpgProgram>>(), result.live.epgByChannel)
        assertFalse(result.live.epgLoaded)
    }

    @Test
    fun currentEpgResponseIsApplied() {
        val state = TvBoxUiState(appSettings = AppSettings(epgUrl = "https://epg.example/list.xml"))
        val programs = mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L)))
        val result = state.applyEpgResult("https://epg.example/list.xml", programs)
        assertEquals(programs, result.live.epgByChannel)
        assertTrue(result.live.epgLoaded)
    }

    /** 回归：取消必须被识别为“非失败”，不能生成错误提示。 */
    @Test
    fun cancellationIsNotTreatedAsFailure() {
        assertNull(CancellationException("请求已被新请求取代").uiFailureMessageOrNull())
    }

    @Test
    fun nonCancellationFailureStillProducesMessage() {
        assertEquals("网络超时，请稍后重试", IOException("timeout").uiFailureMessageOrNull())
    }
}
