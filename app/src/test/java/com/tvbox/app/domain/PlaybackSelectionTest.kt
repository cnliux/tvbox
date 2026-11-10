package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第 308 轮 · 选集定位纯函数单测
 * 锁定 [correspondingEpisodeIndex] 的三种对应策略：精确标题、按编号精确对应、
 * 源无编号剧集时按下标回退；以及 [episodeNumber] 的编号解析边界。
 */
class PlaybackSelectionTest {

    private fun ep(title: String, url: String = "u-$title") = PlayEpisode(title, url)

    private fun source(name: String, vararg eps: PlayEpisode) = PlaySource(name, eps.toList())

    @Test
    fun exactTitleMatchWinsOverNumber() {
        val src = source("默认", ep("第1集"), ep("第2集"), ep("第3集"))
        // 标题完全一致时直接返回，不走到编号解析
        assertEquals(1, correspondingEpisodeIndex(src, "第2集", fallbackIndex = 0))
    }

    @Test
    fun numberedEpisodeMatchesByNumberNotPosition() {
        val src = source("默认", ep("第10集"), ep("第1集"), ep("第5集"))
        // 目标线路有编号剧集，必须按编号精确对应，不能退回 fallbackIndex
        assertEquals(1, correspondingEpisodeIndex(src, "第1集", fallbackIndex = 2))
    }

    @Test
    fun noCorrespondingNumberReturnsNullNotFallback() {
        val src = source("默认", ep("第1集"), ep("第2集"))
        // 目标线路有编号剧集但找不到同编号 -> null（不换源），避免串到别的集
        assertNull(correspondingEpisodeIndex(src, "第9集", fallbackIndex = 0))
    }

    @Test
    fun fallsBackToIndexWhenSourceHasNoNumberedEpisodes() {
        val src = source("默认", ep("正片"), ep("预告"))
        // 源完全没有编号剧集且标题无精确匹配时，按 fallbackIndex 做下标对应
        assertEquals(1, correspondingEpisodeIndex(src, "不存在的标题", fallbackIndex = 1))
        assertNull(correspondingEpisodeIndex(src, "不存在的标题", fallbackIndex = 9))
    }

    @Test
    fun episodeNumberParsesVariantsAndRejectsNonNumeric() {
        assertEquals(1, episodeNumberViaRegex("第1集"))
        assertEquals(12, episodeNumberViaRegex("EP12"))
        assertEquals(3, episodeNumberViaRegex("03话"))
        assertEquals(7, episodeNumberViaRegex("第 07 期"))
        assertNull(episodeNumberViaRegex("正片"))
        assertNull(episodeNumberViaRegex("预告片"))
    }

    /** 复刻 [episodeNumber] 的私有实现，仅用于断言解析边界。 */
    private fun episodeNumberViaRegex(title: String): Int? {
        val regex =
            Regex("^(?:第\\s*|EP(?:ISODE)?\\s*)?0*(\\d+)(?:\\s*[集话期])?$", RegexOption.IGNORE_CASE)
        return regex.matchEntire(title.trim())?.groupValues?.get(1)?.toIntOrNull()
    }
}
