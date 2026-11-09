package com.tvbox.app.domain

// 预编译：correspondingEpisodeIndex 会对每条线路的每个剧集标题调用，逐次构造正则开销显著。
private val EPISODE_NUMBER_REGEX =
    Regex("^(?:第\\s*|EP(?:ISODE)?\\s*)?0*(\\d+)(?:\\s*[集话期])?$", RegexOption.IGNORE_CASE)

fun correspondingEpisodeIndex(source: PlaySource, title: String, fallbackIndex: Int): Int? {
    val exact = source.episodes.indexOfFirst { it.title == title }
    if (exact >= 0) return exact
    val number = episodeNumber(title)
    // 目标线路有编号剧集时，必须按编号精确对应；找不到同编号就返回 null（不换源），
    // 不能退回 fallbackIndex —— 否则会串到完全不同的另一集（见 PlaybackRecoveryTest
    // noCorrespondingEpisodeMeansNoAutomaticSwitch）。只有线路根本没有编号剧集时，
    // 才按 fallbackIndex 做下标对应。
    if (number != null && source.episodes.any { episodeNumber(it.title) != null }) {
        return source.episodes.indexOfFirst { episodeNumber(it.title) == number }.takeIf { it >= 0 }
    }
    return fallbackIndex.takeIf { it in source.episodes.indices }
}

/**
 * 历史续播定位：优先按集地址在影片各线路中反查线路/集下标（换源后地址不变），
 * 地址找不到时退回保存的下标，最后回退主线路首集。
 */
fun resolveHistoryPosition(movie: Movie, item: WatchHistoryItem): Pair<Int, Int> {
    val sourceIndexByUrl = movie.playSources.indexOfFirst { source ->
        source.episodes.any { episode -> episode.url == item.episodeUrl }
    }
    val sourceIndex = when {
        sourceIndexByUrl >= 0 -> sourceIndexByUrl
        item.sourceIndex in movie.playSources.indices -> item.sourceIndex
        else -> movie.preferredSourceIndex()
    }.coerceAtLeast(0)

    val episodes = movie.playSources.getOrNull(sourceIndex)?.episodes.orEmpty()
    val episodeIndexByUrl = episodes.indexOfFirst { it.url == item.episodeUrl }
    val episodeIndex = when {
        episodeIndexByUrl >= 0 -> episodeIndexByUrl
        item.episodeIndex in episodes.indices -> item.episodeIndex
        else -> 0
    }.coerceAtLeast(0)

    return sourceIndex to episodeIndex
}

private fun episodeNumber(title: String): Int? =
    EPISODE_NUMBER_REGEX.matchEntire(title.trim())?.groupValues?.get(1)?.toIntOrNull()
