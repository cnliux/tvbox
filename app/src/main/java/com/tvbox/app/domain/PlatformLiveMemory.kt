package com.tvbox.app.domain

/**
 * 平台直播记忆状态层（第 92/118/159 轮）。
 * 上次观看的平台/分类/房间与「正在直播」角标都基于本机缓存，不新增轮询依赖。
 */

data class LastWatchedPlatform(
    val site: String,
    val categoryId: String,
    val roomId: String,
    val atMs: Long = 0L,
)

private const val PLATFORM_MEMORY_VERSION = "v1"

fun encodeLastWatchedPlatform(last: LastWatchedPlatform?): String = if (last == null) {
    PLATFORM_MEMORY_VERSION
} else {
    listOf(PLATFORM_MEMORY_VERSION, last.site, last.categoryId, last.roomId, last.atMs)
        .joinToString("|")
}

fun decodeLastWatchedPlatform(raw: String?): LastWatchedPlatform? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('|')
    if (parts.size < 5 || parts[0] != PLATFORM_MEMORY_VERSION) return null
    val atMs = parts[4].toLongOrNull() ?: return null
    return LastWatchedPlatform(parts[1], parts[2], parts[3], atMs.coerceAtLeast(0L))
}

fun lastWatchedLabel(last: LastWatchedPlatform?): String = last?.let {
    if (it.site.isBlank()) "上次观看：无" else "上次观看：${it.site}"
} ?: "上次观看：无"

enum class LiveBadge { Live, Unknown, Offline }

/**
 * 角标基于最近一次「确认在播」的时间戳与本地 TTL，避免为收藏卡片做高频轮询；
 * 缓存过期后显示未知而不是错误地标记为未开播。
 */
fun resolveLiveBadge(
    lastSeenLiveAtMs: Long,
    nowMs: Long,
    ttlMs: Long = 10L * 60L * 1000L,
): LiveBadge = when {
    lastSeenLiveAtMs <= 0L -> LiveBadge.Unknown
    nowMs - lastSeenLiveAtMs <= ttlMs -> LiveBadge.Live
    else -> LiveBadge.Unknown
}

fun liveBadgeLabel(badge: LiveBadge): String = when (badge) {
    LiveBadge.Live -> "正在直播"
    LiveBadge.Unknown -> ""
    LiveBadge.Offline -> "未开播"
}
