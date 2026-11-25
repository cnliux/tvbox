package com.tvbox.app.domain

import kotlinx.serialization.Serializable

/**
 * 新剧集提示的文案与语义标记，供历史记录 / 追剧列表 / 详情页统一渲染。
 * [confirmed] = true 表示已确认总集数上涨（橙色）；false 表示仅本机记录内还有未看集数（绿色）。
 */
data class NewEpisodeHint(
    val label: String,
    val confirmed: Boolean,
)

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
     * #317：首次加入历史的时间（epoch ms）。0 表示旧数据，排序时回退到 [updatedAtEpochMs]。
     * 与 updatedAt（每次观看都会刷新）分离，用于「添加时间」排序。
     */
    val addedAtEpochMs: Long = 0,
    /**
     * 保存这条记录时该线路的总集数；0 表示未知（旧数据/单集影片）。
     *
     * 历史列表用它回答用户最关心的问题：「我看完之后，是不是又更新了新集？」
     * 只依赖本机记录，不需要每次进历史都去请求片源。
     */
    val episodeCount: Int = 0,
    /**
     * 跨多个播放源检查后已知的最大总集数；0 表示尚未检查或未知。
     * 与 [episodeCount] 取较大值作为有效集数，用于发现新增剧集。
     */
    val latestEpisodeCount: Int = 0,
    /** 最近一次跨源更新检查时间（epoch ms）；0 表示从未检查。 */
    val lastUpdateCheckEpochMs: Long = 0,
) {
    /** 本机已知的最大总集数（保存时线路集数与跨源检查结果取并集）。 */
    val knownEpisodeCount: Int
        get() = maxOf(episodeCount.coerceAtLeast(0), latestEpisodeCount.coerceAtLeast(0))

    /** 上次观看之后还有多少集没看；0 表示没有更多可看（或集数未知）。 */
    val unwatchedEpisodes: Int
        get() {
            val total = knownEpisodeCount
            if (total <= 0) return 0
            return (total - episodeIndex - 1).coerceAtLeast(0)
        }

    /** 是否由跨源检查确认了比保存时更高的总集数（「更新至 N 集」语义）。 */
    val hasConfirmedNewerTotal: Boolean
        get() = latestEpisodeCount > episodeCount.coerceAtLeast(0) && latestEpisodeCount > 0

    /**
     * 统一「新剧集」提示模型：历史记录 / 追剧列表 / 详情页三处共用，由 UI 层统一渲染。
     * null 表示没有新集（或集数未知），不需要提示。
     */
    val newEpisodeHint: NewEpisodeHint?
        get() {
            val remaining = unwatchedEpisodes
            if (remaining <= 0) return null
            return if (hasConfirmedNewerTotal) {
                // 跨源检查确认总集数上涨：明确「更新至最新」。
                NewEpisodeHint("更新至 $knownEpisodeCount 集", confirmed = true)
            } else {
                // 仅本机记录内还有未看集数：用增量表达，避免误导为已确认更新。
                NewEpisodeHint("+$remaining 集", confirmed = false)
            }
        }

    /** 历史行的更新提示文案（兼容旧调用）；null 表示不需要提示。 */
    val updateHint: String?
        get() = newEpisodeHint?.label

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

/**
 * 观看历史序列化后的体积上限（估算值，按字符串字段字符数近似）。
 * S3-15：只按条数截断可能让历史串过大（每个条目含海报/剧集 URL），
 * 用体积上限兜底，避免偏好存储无界增长。
 */
const val MAX_HISTORY_BYTES: Long = 512 * 1024

/**
 * 单条观看历史记录的近似体积（按各字符串字段的字符数累加，含少量结构开销）。
 * 纯函数，便于单测；不必精确，只用于「按体积裁剪」的排序与截断。
 */
fun estimateBytes(item: WatchHistoryItem): Long =
    item.apiLineId.length +
        item.apiLineName.length +
        item.movieName.length +
        item.posterUrl.length +
        item.typeName.length +
        item.remarks.length +
        item.sourceName.length +
        item.episodeTitle.length +
        item.episodeUrl.length +
        64L // 数值字段 + 序列化结构开销的固定近似

/**
 * 进度单调闸（T5-31）：同一集记录已存在且新写入进度更早时，沿用旧值，
 * 确保「进度不倒退」。纯函数，便于单测。
 */
fun monotonicPosition(prev: WatchHistoryItem?, incoming: WatchHistoryItem): Long =
    if (prev != null && incoming.positionMs < prev.positionMs) prev.positionMs else incoming.positionMs

/**
 * 按体积上限裁剪：列表已按「最新在前」排序，从尾部（最旧）开始移除，
 * 直到估算总和 <= [maxBytes] 或只剩 1 条。保留最新条目在前。
 */
fun trimByVolume(
    items: List<WatchHistoryItem>,
    maxBytes: Long = MAX_HISTORY_BYTES,
): List<WatchHistoryItem> {
    if (items.size <= 1) return items
    var total = items.sumOf(::estimateBytes)
    var endExclusive = items.size
    while (endExclusive > 1 && total > maxBytes) {
        val removed = items[endExclusive - 1]
        total -= estimateBytes(removed)
        endExclusive--
    }
    return items.subList(0, endExclusive)
}
