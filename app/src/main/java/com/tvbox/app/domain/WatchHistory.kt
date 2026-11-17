package com.tvbox.app.domain

import kotlinx.serialization.Serializable

@Serializable
data class WatchHistoryItem(
    val movieId: Int,
    val apiLineId: String = "ruyi",
    val apiLineName: String = "如意",
    val movieName: String,
    val posterUrl: String,
    val typeName: String,
    val remarks: String,
    val sourceIndex: Int,
    val sourceName: String,
    val episodeIndex: Int,
    val episodeTitle: String,
    val episodeUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * 保存这条记录时该线路的总集数；0 表示未知（旧数据/单集影片）。
     *
     * 历史列表用它回答用户最关心的问题：「我看完之后，是不是又更新了新集？」
     * 只依赖本机记录，不需要每次进历史都去请求片源。
     */
    val episodeCount: Int = 0,
) {
    /** 上次观看之后还有多少集没看；0 表示没有更多可看（或集数未知）。 */
    val unwatchedEpisodes: Int
        get() = if (episodeCount <= 0) 0 else (episodeCount - episodeIndex - 1).coerceAtLeast(0)

    /** 历史行的更新提示文案；null 表示不需要提示。 */
    val updateHint: String?
        get() = unwatchedEpisodes.takeIf { it > 0 }?.let { "还有 $it 集" }

    val progressPercent: Int
        get() {
            if (durationMs <= 0L || positionMs <= 0L) return 0
            return ((positionMs * 100) / durationMs).coerceIn(0, 100).toInt()
        }
}

fun List<WatchHistoryItem>.continueWatching(limit: Int = 6): List<WatchHistoryItem> =
    sortedByDescending { it.updatedAtEpochMs }
        .distinctBy { "${it.apiLineId}:${it.movieId}" }
        .filter { it.positionMs > 0 && (it.durationMs <= 0 || it.positionMs.toDouble() / it.durationMs < 0.95) }
        .take(limit.coerceAtLeast(0))
