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

// 去掉「1.第1集」「01-第01集」这类重复编号层：先剥一层数字前缀后，若剩余仍是独立编号则继续剥。
private val EPISODE_LEADING_NUMBER_SEPARATOR_REGEX =
    Regex("^[0-9]+\\s*[.、,，:：\\-–—]\\s*")

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

/** 展示用的剧集标题：去掉源里的「1.」「第01集」「EP12」这类编号。
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
    var stripped = EPISODE_PREFIX_REGEX.replace(trimmed, "").trim()
    // 处理「1.第一集」「01.第01集」等叠加编号：剥掉外层数字前缀后若仍有冗余编号前缀则继续剥。
    repeat(2) {
        if (stripped.isEmpty()) return@repeat
        if (episodeNumber(stripped) != null) return@repeat
        val next = EPISODE_PREFIX_REGEX.replace(stripped, "").trim()
        val next2 = if (next == stripped) {
            EPISODE_LEADING_NUMBER_SEPARATOR_REGEX.replace(stripped, "").trim()
        } else {
            next
        }
        if (next2.isEmpty() || next2 == stripped) return@repeat
        stripped = next2
    }
    // 剩余内容若仅是编号，优先展示有意义文本；否则回退到编号。
    if (stripped.isEmpty()) return episodeNumber(trimmed)?.toString() ?: trimmed
    episodeNumber(stripped)?.let { number ->
        // 「1. 2」这类纯编号叠加，保留单一编号；「1.第1集」应已被剥成「第1集」再按需处理。
        if (stripped == "$number") return "$number"
    }
    return stripped
}

/** 详情页「播放」按钮的续播目标：线路、集数，以及该集已看到的毫秒数。 */
data class PlaybackResumePoint(
    val sourceIndex: Int,
    val episodeIndex: Int,
    val positionMs: Long,
)

/** 看完的判定：进度已到 95% 以上视为看完，再点播放应从本集开头开始。 */
private const val RESUME_FINISHED_RATIO = 0.95

/**
 * 从本机观看历史里找出这部影片的续播点。
 *
 * 只看同一部影片（同源同 id），并按地址反查线路/集数——线路顺序或剧集顺序
 * 变了（源站重排、备用线路补齐）也能落到正确的一集，而不是按过期下标串集。
 * 找不到记录、进度无效（<=0）或已看完时返回 null，由调用方按「从头播放」处理。
 */
fun findResumePoint(
    movie: Movie,
    historyItems: List<WatchHistoryItem>,
): PlaybackResumePoint? {
    val item = historyItems.firstOrNull {
        it.movieId == movie.id && it.apiLineId == movie.apiLineId
    } ?: return null
    if (item.positionMs <= 0L) return null
    // 已看到片尾：续播会立刻触发「自动下一集」，不如从头开始。
    if (item.durationMs > 0L && item.positionMs.toDouble() / item.durationMs >= RESUME_FINISHED_RATIO) return null
    val (sourceIndex, episodeIndex) = resolveHistoryPosition(movie, item)
    if (movie.playSources.getOrNull(sourceIndex)?.episodes?.getOrNull(episodeIndex) == null) return null
    return PlaybackResumePoint(sourceIndex, episodeIndex, item.positionMs)
}

/**
 * 详情页「播放」按钮的续播进度：只在选中的正好是历史记录那一集时返回已看进度，
 * 否则返回 0（用户主动点了别的集，应当从这一集开头播放）。
 */
fun resumePositionForEpisode(
    movie: Movie,
    historyItems: List<WatchHistoryItem>,
    sourceIndex: Int,
    episodeIndex: Int,
): Long {
    val point = findResumePoint(movie, historyItems) ?: return 0L
    if (point.sourceIndex != sourceIndex || point.episodeIndex != episodeIndex) return 0L
    return point.positionMs
}
