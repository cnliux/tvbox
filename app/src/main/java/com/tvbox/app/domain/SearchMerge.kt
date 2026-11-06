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
