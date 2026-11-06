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
