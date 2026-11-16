package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMergeTest {

    @Test
    fun emptyIncomingReturnsExisting() {
        val existing = listOf(movie("line1", 1, "A"))
        assertEquals(existing, mergeMoviesBySourceAndId(existing, emptyList()))
    }

    @Test
    fun deduplicatesByApiLineAndIdPreservingOrder() {
        val existing = listOf(
            movie("line1", 1, "A"),
            movie("line2", 1, "B"),
        )
        val incoming = listOf(
            movie("line1", 1, "A-dup"),
            movie("line1", 2, "C"),
            movie("line2", 1, "B-dup"),
        )
        val merged = mergeMoviesBySourceAndId(existing, incoming)
        assertEquals(listOf("A", "B", "C"), merged.map { it.name })
        assertEquals(3, merged.size)
    }

    @Test
    fun sameMovieIdDifferentApiLineIsNotDeduplicated() {
        val existing = listOf(movie("line1", 42, "X"))
        val incoming = listOf(movie("line2", 42, "Y"))
        val merged = mergeMoviesBySourceAndId(existing, incoming)
        assertEquals(2, merged.size)
        assertEquals(listOf("line1", "line2"), merged.map { it.apiLineId })
    }

    @Test
    fun buildGroupsFollowsCustomSourceOrderAndKeepsEmptySourcesOut() {
        val results = mapOf(
            "line2" to listOf(movie("line2", 1, "B")),
            "line1" to listOf(movie("line1", 1, "A")),
            "line3" to emptyList(),
        )
        // 用户自定义顺序是 line2, line1, line3；line3 无结果应被剔除。
        val ordered = listOf(
            ApiLine("line2", "源二", listOf("http://2")),
            ApiLine("line1", "源一", listOf("http://1")),
            ApiLine("line3", "源三", listOf("http://3")),
        )
        val groups = buildSearchSourceGroups(results, ordered)
        assertEquals(listOf("line2", "line1"), groups.map { it.sourceId })
        assertEquals(listOf("源二", "源一"), groups.map { it.sourceName })
        assertEquals(listOf(1, 1), groups.map { it.movies.size })
    }

    @Test
    fun buildGroupsAppendsUnknownSourceAndFallsBackToMovieName() {
        val results = mapOf(
            "line1" to listOf(movie("line1", 1, "A")),
            "gone" to listOf(movie("gone", 9, "Z").copy(apiLineName = "旧源")),
        )
        val ordered = listOf(ApiLine("line1", "源一", listOf("http://1")))
        val groups = buildSearchSourceGroups(results, ordered)
        assertEquals(listOf("line1", "gone"), groups.map { it.sourceId })
        // 配置外的源用影片携带的来源名兜底，不丢结果。
        assertEquals("旧源", groups[1].sourceName)
    }

    @Test
    fun buildGroupsReturnsEmptyForNoResults() {
        assertEquals(emptyList<SearchSourceGroup>(), buildSearchSourceGroups(emptyMap(), emptyList()))
    }

    private fun movie(apiLineId: String, id: Int, name: String): Movie = Movie(
        id = id,
        apiLineId = apiLineId,
        apiLineName = apiLineId,
        name = name,
        typeId = 0,
        typeName = "",
        posterUrl = "",
        remarks = "",
        year = "",
        area = "",
        language = "",
        actor = "",
        director = "",
        duration = "",
        description = "",
        playSources = emptyList(),
    )
}
