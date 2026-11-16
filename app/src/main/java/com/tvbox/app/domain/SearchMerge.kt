package com.tvbox.app.domain

/**
 * 合并多来源渐进搜索结果，按 (apiLineId, movieId) 去重并保持先到先得顺序。
 */
fun mergeMoviesBySourceAndId(existing: List<Movie>, incoming: List<Movie>): List<Movie> {
    if (incoming.isEmpty()) return existing
    val seen = existing.mapTo(mutableSetOf()) { "${it.apiLineId}:${it.id}" }
    val merged = existing.toMutableList()
    incoming.forEach { movie ->
        if (seen.add("${movie.apiLineId}:${movie.id}")) {
            merged += movie
        }
    }
    return merged
}

/** 手机端按源分组的一组搜索结果。 */
data class SearchSourceGroup(
    val sourceId: String,
    val sourceName: String,
    val movies: List<Movie>,
)

/**
 * 把每源原始结果整理成有序分组：按用户自定义源顺序（[orderedSources]）排列，
 * 仅保留有结果的源；名称优先取源配置里的名字，回退到影片携带的来源名。
 * 数据中若出现配置外的源（例如刚被删掉但结果仍在），追加在末尾，保证不丢结果。
 */
fun buildSearchSourceGroups(
    resultsBySource: Map<String, List<Movie>>,
    orderedSources: List<ApiLine>,
): List<SearchSourceGroup> {
    if (resultsBySource.isEmpty()) return emptyList()
    val nameById = orderedSources.associate { it.id to it.name }
    val fallbackNameById = resultsBySource.values.flatten()
        .associate { it.apiLineId to it.apiLineName }
    val orderedIds = orderedSources.map { it.id }
    val allIds = resultsBySource.keys
    val sortedIds = orderedIds.filter { it in allIds } + (allIds - orderedIds.toSet()).sorted()
    return sortedIds.mapNotNull { id ->
        val movies = resultsBySource[id].orEmpty()
        if (movies.isEmpty()) return@mapNotNull null
        SearchSourceGroup(
            sourceId = id,
            sourceName = nameById[id]?.takeIf { it.isNotBlank() }
                ?: fallbackNameById[id]?.takeIf { it.isNotBlank() }
                ?: id,
            movies = movies,
        )
    }
}
