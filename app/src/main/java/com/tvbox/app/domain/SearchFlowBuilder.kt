package com.tvbox.app.domain

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * 一次搜索会话的增量状态，ViewModel 直接据此写回 UI state。
 *
 * 与 [MultiSourceSearchUpdate] 的区别：这里已经跨源去重合并成最终扁平列表，
 * 并保留「按源分组」的原始快照（手机端分组展示用）。
 */
data class SearchEmission(
    val results: List<Movie>,
    val resultsBySource: Map<String, List<Movie>>,
    val completedSources: Int,
    val totalSources: Int,
)

/**
 * 把「单个查询词」构建成可取消的冷搜索流（S5-27）。
 *
 * 职责（与旧 ViewModel.submitSearch 内联逻辑等价，但提升为纯 Flow）：
 * 1. 拼音首字母（如 xclf）先扩展为候选中文片名，多候选并行搜索以缩短总延迟；
 * 2. 主候选（可信度最高）一旦产出结果，忽略其余低可信候选的增量，避免稀释主结果；
 * 3. 增量结果按 (apiLineId, movieId) 去重合并后逐个发射。
 *
 * 关键点：返回的是**冷流且可取消**。上游 `flatMapLatest` 在 query 变化时会取消本流，
 * 从而保证「输 a → 立刻改 ab → 立刻改 abc」三个并发搜索中，旧搜索的迟到回包不会
 * 覆盖新搜索的结果（用户投诉「搜索很蠢 / 结果闪一下跳回无关内容」的根因）。
 *
 * [search] 是对仓库搜索的抽象：`suspend (apiLineId, page, keyword) -> Flow<MultiSourceSearchUpdate>`。
 * 测试时注入假实现即可复现乱序回包并断言最终态 = 最后一个 query 的结果。
 */
fun buildSearchFlow(
    query: String,
    selectedApiLineId: String,
    expandInitials: suspend (String, Int) -> List<String>,
    suggestionCount: Int,
    search: suspend (apiLineId: String, page: Int, keyword: String) -> Flow<MultiSourceSearchUpdate>,
): Flow<SearchEmission> = channelFlow {
    val keywords = expandInitials(query, suggestionCount)
        .ifEmpty { listOf(query) }
        .distinct()
    var mergedResults: List<Movie> = emptyList()
    var mergedBySource: Map<String, List<Movie>> = emptyMap()
    var totalSources = 0
    val foundResults = BooleanArray(keywords.size)
    coroutineScope {
        keywords.forEachIndexed { index, keyword ->
            launch {
                search(selectedApiLineId, 1, keyword).collect { update ->
                    // 主候选已出结果后，较低可信候选的增量不再并入，避免稀释主结果。
                    if (foundResults[0] && index > 0) return@collect
                    totalSources = maxOf(totalSources, update.totalSources)
                    if (update.movies.isNotEmpty() && index == 0) foundResults[0] = true
                    mergedResults = mergeMoviesBySourceAndId(mergedResults, update.movies)
                    mergedBySource = mergeMoviesBySourceMap(mergedBySource, update.moviesBySource)
                    // channelFlow 允许多个 launch 子协程并发 send；外层取消时自动关闭通道，
                    // 这正是 flatMapLatest 切换 query 时取消旧搜索的语义来源。
                    send(
                        SearchEmission(
                            results = mergedResults,
                            resultsBySource = mergedBySource,
                            completedSources = update.completedSources,
                            totalSources = maxOf(totalSources, update.completedSources),
                        ),
                    )
                }
            }
        }
    }
}

/** 每源原始结果按 (apiLineId, movieId) 去重合并；源与源之间互不干扰，保留各源分组。 */
internal fun mergeMoviesBySourceMap(
    existing: Map<String, List<Movie>>,
    incoming: Map<String, List<Movie>>,
): Map<String, List<Movie>> {
    if (incoming.isEmpty()) return existing
    val merged = existing.toMutableMap()
    incoming.forEach { (sourceId, movies) ->
        merged[sourceId] = mergeMoviesBySourceAndId(merged[sourceId].orEmpty(), movies)
    }
    return merged
}
