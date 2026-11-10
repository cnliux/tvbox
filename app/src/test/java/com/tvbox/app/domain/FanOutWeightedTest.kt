package com.tvbox.app.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanOutWeightedTest {

    private fun movie(id: Int): Movie = Movie(
        id = id,
        apiLineId = "a",
        apiLineName = "A",
        name = "影片$id",
        typeId = 1,
        typeName = "电视剧",
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

    @Test
    fun weightedTimeoutsScaleWithWeight() {
        assertEquals(
            listOf(1_000L, 4_000L),
            weightedTimeouts(listOf(25, 100), 4_000L, minTimeoutMs = 100L),
        )
        assertEquals(listOf(500L, 4_000L), weightedTimeouts(listOf(0, 100), 4_000L))
    }

    @Test
    fun emptyWeightsProduceEmptyTimeouts() {
        assertTrue(weightedTimeouts(emptyList(), 4_000L).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveBaseTimeoutIsRejected() {
        weightedTimeouts(listOf(1), 0L)
    }

    @Test
    fun incrementalSearchDeduplicatesAcrossSources() = runBlocking {
        val shared = movie(7)
        val sources = listOf(
            WeightedSourceTask(FanOutTask<List<Movie>> { listOf(shared) }, weight = 100),
            WeightedSourceTask(
                FanOutTask<List<Movie>> { delay(5L); listOf(shared, movie(8)) },
                weight = 50,
            ),
        )
        val updates = searchMoviesIncrementally(sources, baseTimeoutMs = 1_000L, maxParallel = 2).toList()

        assertEquals(2, updates.size)
        assertEquals(listOf(1, 2), updates.map { it.completedSources })
        assertEquals(listOf(7, 8), updates.last().movies.map { it.id })
        assertTrue(updates.all { it.totalSources == 2 })
    }

    @Test
    fun emptySourcesEmitNothing() = runBlocking {
        assertTrue(
            searchMoviesIncrementally(emptyList<WeightedSourceTask<List<Movie>>>()).toList().isEmpty(),
        )
    }
}
