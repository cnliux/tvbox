package com.tvbox.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UseCasesTest {

    private fun movie(id: Int, name: String, typeName: String = "电视剧"): Movie = Movie(
        id = id,
        apiLineId = "a",
        apiLineName = "A",
        name = name,
        typeId = 1,
        typeName = typeName,
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

    private fun paged(vararg movies: Movie) = PagedMovies(
        page = 1,
        pageCount = 1,
        total = movies.size,
        apiLine = ApiLine("a", "A", listOf("https://example.com")),
        categories = emptyList(),
        movies = movies.toList(),
    )

    private class FakeMovieRepository(private val result: PagedMovies) : MovieRepository {
        var lastTypeIds: List<Int>? = null
        override val apiLines: List<ApiLine> = emptyList()
        override val allApiLines: List<ApiLine> = emptyList()
        override fun updateCustomApiLines(customApiLines: List<ApiLine>) = Unit
        override fun setDisabledLineIds(disabledIds: Set<String>) = Unit
        override suspend fun testSpeed(apiLineId: String): Long? = null
        override suspend fun getCategories(apiLineId: String): List<Category> = emptyList()
        override suspend fun getMovies(
            apiLineId: String,
            page: Int,
            typeId: Int?,
            keyword: String?,
        ): PagedMovies = result

        override suspend fun getMoviesByTypeIds(
            apiLineId: String,
            page: Int,
            typeIds: List<Int>,
        ): PagedMovies {
            lastTypeIds = typeIds
            return result
        }

        override suspend fun getDetail(apiLineId: String, id: Int): Movie? = null
    }

    @Test
    fun homeFeedFiltersBlockedContent() = runBlocking {
        val repository = FakeMovieRepository(paged(movie(1, "正常剧集"), movie(2, "伦理片")))
        val result = GetHomeFeedUseCase(repository)("a", 1)
        assertEquals(listOf(1), result.movies.map { it.id })
        assertEquals(2, result.total)
    }

    @Test
    fun homeFeedUsesTypeIdsPathWhenProvided() = runBlocking {
        val repository = FakeMovieRepository(paged())
        GetHomeFeedUseCase(repository)("a", 1, listOf(2, 3))
        assertEquals(listOf(2, 3), repository.lastTypeIds)
    }

    @Test
    fun resolvesChannelByNumber() = runBlocking {
        val channel = LiveChannel(number = 12, groupName = "央视", name = "CCTV-1", lines = emptyList())
        val repository = object : LiveRepository {
            override suspend fun getChannels(): List<LiveChannel> = listOf(channel)
            override fun setSourceConfig(customUrls: List<String>, defaultGroup: String, defaultIndex: Int) = Unit
        }
        val useCase = ResolveLiveStreamUseCase(repository)
        assertEquals(channel, useCase.byNumber(useCase(), 12))
        assertNull(useCase.byNumber(useCase(), 99))
    }
}
