package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.LastWatchedPlatform
import com.tvbox.app.domain.decodeLastWatchedPlatform
import com.tvbox.app.domain.encodeLastWatchedPlatform
import com.tvbox.app.domain.resolveLiveBadge

/**
 * 平台直播「上次观看」的本机持久化（第 271–280 轮）。
 *
 * 编解码与在播角标判定留在 domain（可 JVM 单测），这里只负责 SharedPreferences 读写；
 * `lazy` 避免在教学启动路径同步解析 XML。不保存房间标题或封面，只留标识与时间戳。
 */
class PlatformLiveMemoryStore(context: Context) {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(): LastWatchedPlatform? = decodeLastWatchedPlatform(prefs.getString(KEY_LAST_WATCHED, null))

    fun save(last: LastWatchedPlatform) {
        prefs.edit().putString(KEY_LAST_WATCHED, encodeLastWatchedPlatform(last)).apply()
    }

    fun save(site: String, categoryId: String, roomId: String, atMs: Long = System.currentTimeMillis()) {
        save(LastWatchedPlatform(site, categoryId, roomId, atMs))
    }

    /** 收藏卡片角标：只依据本机「最近确认在播」的时间戳，不做高频轮询。 */
    fun lastSeenLiveAtMs(): Long = prefs.getLong(KEY_LAST_SEEN_LIVE, 0L)

    fun markSeenLive(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SEEN_LIVE, atMs).apply()
    }

    fun isBadgeLive(nowMs: Long = System.currentTimeMillis()) =
        resolveLiveBadge(lastSeenLiveAtMs(), nowMs) == com.tvbox.app.domain.LiveBadge.Live

    fun clear() {
        prefs.edit().remove(KEY_LAST_WATCHED).remove(KEY_LAST_SEEN_LIVE).apply()
    }

    companion object {
        private const val PREFS_NAME = "platform_live_memory"
        private const val KEY_LAST_WATCHED = "last_watched"
        private const val KEY_LAST_SEEN_LIVE = "last_seen_live"
    }
}
