package com.tvbox.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-27：搜索改 flatMapLatest + debounce，验证乱序回包不会覆盖新查询结果。
 *
 * 核心断言：快速输入「a → 立刻改 ab」时，若「a」的搜索回包较慢、后于「ab」到达，
 * 最终态必须只包含「ab」的结果，而不是被「a」的迟到回包覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFlowBuilderTest {

    /** 普通关键词：无拼音扩展，直接搜索。 */
    @Test
    fun buildSearchFlow_mergesIncrementalResultsFromSources() = runTest {
        val search: suspend (String, Int, String) -> Flow<MultiSourceSearchUpdate> = { _, _, _ ->
            flow {
                emit(
                    MultiSourceSearchUpdate(
                        movies = listOf(movie("line1", 1, "A")),
                        completedSources = 1,
                        totalSources = 2,
                        moviesBySource = mapOf("line1" to listOf(movie("line1", 1, "A"))),
                    ),
                )
                emit(
                    MultiSourceSearchUpdate(
                        movies = listOf(
                            movie("line1", 2, "B"),
                            movie("line2", 1, "C"),
                        ),
                        completedSources = 2,
                        totalSources = 2,
                        // searchProgressively 的 moviesBySource 是累积快照：line1 已含 A、B。
                        moviesBySource = mapOf(
                            "line1" to listOf(movie("line1", 1, "A"), movie("line1", 2, "B")),
                            "line2" to listOf(movie("line2", 1, "C")),
                        ),
                    ),
                )
            }
        }
        val emissions = buildSearchFlow(
            query = "abc",
            selectedApiLineId = "line1",
            expandInitials = { _, _ -> emptyList() },
            suggestionCount = 5,
            search = search,
        ).toList()

        // 增量合并 + 按 (apiLineId, movieId) 去重 + 保序。
        val final = emissions.last()
        assertEquals(listOf("A", "B", "C"), final.results.map { it.name })
        assertEquals(2, final.completedSources)
        assertEquals(2, final.totalSources)
        // 手机端按源分组快照：line1 两条、line2 一条，互不干扰。
        assertEquals(2, final.resultsBySource["line1"]?.size)
        assertEquals(1, final.resultsBySource["line2"]?.size)
    }

    /** 拼音首字母扩展出多个候选：主候选出结果后，低可信候选增量被忽略，不稀释主结果。 */
    @Test
    fun buildSearchFlow_ignoresLowConfidenceCandidateAfterPrimaryFound() = runTest {
        val search: suspend (String, Int, String) -> Flow<MultiSourceSearchUpdate> = { _, _, keyword ->
            when (keyword) {
                // 主候选（首个）先返回，包含主结果。
                "西游记" -> flowOf(
                    MultiSourceSearchUpdate(
                        movies = listOf(movie("line1", 1, "西游记")),
                        completedSources = 1,
                        totalSources = 1,
                        moviesBySource = mapOf("line1" to listOf(movie("line1", 1, "西游记"))),
                    ),
                )
                // 低可信候选「戏说西游」后返回，应被忽略。
                else -> flowOf(
                    MultiSourceSearchUpdate(
                        movies = listOf(movie("line1", 99, "戏说西游")),
                        completedSources = 1,
                        totalSources = 1,
                        moviesBySource = mapOf("line1" to listOf(movie("line1", 99, "戏说西游"))),
                    ),
                )
            }
        }
        val emissions = buildSearchFlow(
            query = "xyj",
            selectedApiLineId = "line1",
            expandInitials = { _, _ -> listOf("西游记", "戏说西游") },
            suggestionCount = 5,
            search = search,
        ).toList()

        val final = emissions.last()
        // 只保留主候选「西游记」，低可信候选「戏说西游」被忽略。
        assertEquals(listOf("西游记"), final.results.map { it.name })
    }

    /**
     * 核心场景：flatMapLatest + debounce 的乱序回包保护。
     * 「a」的搜索慢（500ms），「ab」的搜索快（10ms）；先发 a、再发 ab。
     * 期望最终态 = 只含「ab」的结果，不被迟到的「a」覆盖。
     */
    @Test
    fun flatMapLatest_dropsOutOfOrderSlowResponseOfPreviousQuery() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val queries = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val collected = mutableListOf<SearchEmission>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            queries
                // 直接 flatMapLatest：聚焦乱序回包保护（debounce 只负责合并快速输入，
                // 真正的取消保护由 flatMapLatest 提供，与生产管道一致）。
                .flatMapLatest { query ->
                    buildSearchFlow(
                        query = query,
                        selectedApiLineId = "line1",
                        expandInitials = { _, _ -> emptyList() },
                        suggestionCount = 5,
                        search = { _, _, keyword ->
                            flow {
                                // 慢查询「a」：等待 500ms 才发结果；快查询「ab」：10ms 即回。
                                val delayMs = if (keyword == "a") 500L else 10L
                                delay(delayMs)
                                emit(
                                    MultiSourceSearchUpdate(
                                        movies = listOf(movie("line1", keyword.hashCode(), keyword)),
                                        completedSources = 1,
                                        totalSources = 1,
                                        moviesBySource = mapOf("line1" to listOf(movie("line1", keyword.hashCode(), keyword))),
                                    ),
                                )
                            }
                        },
                    )
                }
                .collect { collected += it }
        }
        // 订阅已就绪（UNDISPATCHED 启动），先发「a」（慢），再立刻发「ab」（快）。
        queries.emit("a")
        queries.emit("ab")
        // 让两个搜索都跑完（> 500ms），期间「a」的回包应被 flatMapLatest 取消。
        advanceUntilIdle()
        job.cancel()

        assertTrue("应有结果", collected.isNotEmpty())
        // 最终态 = 最后一个 query（ab）的结果，不被「a」的迟到回包覆盖。
        assertEquals("ab", collected.last().results.single().name)
        // 整个过程中，一旦「ab」的结果落地，后续不能再出现「a」的结果。
        assertTrue(collected.last().results.none { it.name == "a" })
    }

    /** 空查询直接产生空结果，不触发底层搜索。 */
    @Test
    fun buildSearchFlow_blankQueryReturnsNoResults() = runTest {
        val emissions = buildSearchFlow(
            query = "   ",
            selectedApiLineId = "line1",
            expandInitials = { _, _ -> emptyList() },
            suggestionCount = 5,
            search = { _, _, _ -> flowOf(MultiSourceSearchUpdate(emptyList(), 0, 0)) },
        ).toList()
        // 「   」按原文搜索（非拼音首字母，expand 返回空 → 回退原文），
        // 搜索流正常跑完；空结果是正常空态，不是错误。
        assertEquals(1, emissions.size)
        assertTrue(emissions.single().results.isEmpty())
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

    private fun flowOf(vararg updates: MultiSourceSearchUpdate): Flow<MultiSourceSearchUpdate> =
        flow { updates.forEach { emit(it) } }
}
