package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeywordExpanderTest {
    @Test
    fun recognizesPinyinInitialQueries() {
        assertTrue(isPinyinInitialQuery("xclf"))
        assertTrue(isPinyinInitialQuery("XCLF"))
        assertTrue(isPinyinInitialQuery(" xclf "))
        assertEquals(false, isPinyinInitialQuery("x"))
        assertEquals(false, isPinyinInitialQuery("abc123"))
        assertEquals(false, isPinyinInitialQuery("小城良方"))
        assertEquals(false, isPinyinInitialQuery(""))
    }

    @Test
    fun parsesSuggestionWords() {
        val raw = """
            {"errorcode":0,"query":"xclf","result":[
                {"face":"","word":"小城良方电视剧"},
                {"face":"","word":"小城良方演员表"}
            ]}
        """.trimIndent()
        assertEquals(listOf("小城良方电视剧", "小城良方演员表"), parseSuggestionWords(raw))
        assertEquals(emptyList<String>(), parseSuggestionWords("not json"))
        assertEquals(emptyList<String>(), parseSuggestionWords(""))
    }

    @Test
    fun stripsMarketingSuffixes() {
        assertEquals("小城良方", normalizeSuggestionTitle("小城良方电视剧在线观看免费版"))
        assertEquals("小城良方", normalizeSuggestionTitle("小城良方演员表全部名单介绍"))
        assertEquals("小城良方", normalizeSuggestionTitle("小城良方百度百科"))
        assertEquals("小城良方", normalizeSuggestionTitle("小城良方电视剧"))
        assertEquals(null, normalizeSuggestionTitle("abc-def"))
        assertEquals(null, normalizeSuggestionTitle("在"))
    }

    @Test
    fun ranksFrequentTitleFirst() {
        val words = listOf(
            "小城粮方电视剧在线观看免费版",
            "小城良方电视剧",
            "小城良方星辰影院",
            "小城良方电视剧剧情介绍",
            "小城良方第一集",
            "小城良方电视剧策驰影院",
            "小城良方演员表全部名单介绍",
            "小城良方剧情分集介绍",
            "小城良方百度百科",
            "小城良方电视剧星辰影院",
        )
        val candidates = extractTitleCandidates(words, limit = 3)
        assertEquals("小城良方", candidates.first())
        assertTrue(candidates.contains("小城粮方"))
        assertTrue(candidates.size <= 3)
    }
}
