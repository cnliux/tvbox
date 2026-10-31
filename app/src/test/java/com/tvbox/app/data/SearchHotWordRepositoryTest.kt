package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchHotWordRepositoryTest {
    @Test
    fun parsesAggregatedRankTitlesAndFiltersBlockedWords() {
        val titles = parseRankTitles(
            """
                {
                  "data": [
                    {"title": "小城良方"},
                    {"title": "  "},
                    {"title": "电影解说"},
                    {"title": "醒来"}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("小城良方", "醒来"), titles)
    }

    @Test
    fun parsesInvalidJsonAsEmptyList() {
        assertEquals(emptyList<String>(), parseRankTitles("<html>error</html>"))
    }

    @Test
    fun composesGroupsInOrderWithoutDuplicates() {
        val words = composeHotWords(
            listOf(
                listOf("庆余年", "狂飙", "《狂飙》"),
                listOf("狂飙", "繁花", "  繁花  "),
                listOf("三体"),
            ),
        )

        assertEquals(listOf("庆余年", "狂飙", "繁花", "三体"), words)
    }

    @Test
    fun composeRespectsLimit() {
        val words = composeHotWords(listOf((1..30).map { "热词$it" }), limit = 18)

        assertEquals(18, words.size)
    }

    @Test
    fun cacheKeepsExpiredWordsAvailableAsFallback() {
        val cache = InMemorySearchHotWordCache()
        cache.put(SearchHotWordCacheRecord(words = listOf("旧热词"), expiresAtEpochMs = 100L))

        assertNull(cache.get(nowMs = 200L))
        assertNotNull(cache.getAny()?.words?.singleOrNull())
        assertEquals("旧热词", cache.getAny()?.words?.singleOrNull())
    }
}
