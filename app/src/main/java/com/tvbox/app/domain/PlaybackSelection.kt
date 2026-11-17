package com.tvbox.app.domain

// 预编译：correspondingEpisodeIndex 会对每条线路的每个剧集标题调用，逐次构造正则开销显著。
// 各源站的编号形态差异很大，必须都覆盖，否则换源时对不上集：
//   "12"、"第12集"、"EP12"、"12."、"12、"、"12 -"、"[12]"、"12集"
private val EPISODE_NUMBER_REGEX =
    Regex(
        "^[\\[\\(【]?\\s*(?:第\\s*|EP(?:ISODE)?\\.?\\s*)?0*(\\d+)" +
            "\\s*[\\]\\)】]?\\s*(?:[集话期回]|[-–—.:、,，])?\\s*$",
        RegexOption.IGNORE_CASE,
    )

// 只剥离开头的编号前缀，且要求后面确实还有内容。
private val EPISODE_PREFIX_REGEX =
    Regex(
        "^[\\[\\(【]?\\s*(?:第\\s*0*(\\d+)\\s*[集话期回]|EP(?:ISODE)?\\.?\\s*0*(\\d+)|0*(\\d+)\\s*[.、,，:：\\-–—])\\s*",
        RegexOption.IGNORE_CASE,
    )

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

/**
 * 展示用的剧集标题：去掉源里的「1.」「第01集」「EP12」这类编号。
 *
 * 各源给同一集的标题五花八门（`1.`、`第01集`、`EP12`），直接显示会让选集网格
 * 变成一排没有意义的数字。但**不能**把编号整段删掉：`第1集 上` 这种标题里编号
 * 是有效信息，只有「整条标题就是一个编号」时才保留编号本身，避免出现空标题。
 */
fun displayEpisodeTitle(title: String, index: Int): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "${index + 1}"
    // 整条标题只是编号：保留编号（否则删成空串）。
    episodeNumber(trimmed)?.let { return "$it" }
    val stripped = EPISODE_PREFIX_REGEX.replace(trimmed, "").trim()
    return stripped.ifEmpty { trimmed }
}
