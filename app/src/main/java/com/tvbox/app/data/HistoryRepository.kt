package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.isBlockedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 观看历史默认保留条数；可由 AppSettings.historyMaxItems 覆盖。 */
const val MAX_HISTORY_ITEMS = 100

// 契约已迁移到 domain；保留同包别名，确保既有 import 与 public API 不变。
typealias HistoryRepository = com.tvbox.app.domain.HistoryRepository

class SharedHistoryRepository(context: Context) : HistoryRepository {
    private val appContext = context.applicationContext
    // lazy：getSharedPreferences 首次调用会同步解析 XML，避免在启动路径（ViewModel 工厂）上读盘。
    private val prefs by lazy { appContext.getSharedPreferences("watch_history", Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun getHistory(): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock { readHistory() ?: emptyList() }
    }

    override suspend fun saveProgress(
        item: WatchHistoryItem,
        maxItems: Int,
    ): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = readHistory() ?: return@withLock emptyList()
            // 播放器尚未 READY 时保存会带 duration=0；若直接覆盖，历史进度条会归零且
            // 提前退出时永久丢失总时长。此时沿用同一集已有记录的时长。
            val previousSameEpisode = history.firstOrNull {
                it.apiLineId == item.apiLineId && it.movieId == item.movieId &&
                    (it.episodeIndex == item.episodeIndex || it.episodeUrl == item.episodeUrl)
            }
            val previousSameMovie = history.firstOrNull {
                it.apiLineId == item.apiLineId && it.movieId == item.movieId
            }
            val durationMs = if (item.durationMs > 0L) item.durationMs else previousSameEpisode?.durationMs ?: 0L
            // #317：addedAt 只在首次加入该影片时写入，后续观看只刷新 updatedAt。
            // 旧数据没有该字段（解码为 0），这里补写当前时间，保证「添加时间」排序可用。
            val nowMs = System.currentTimeMillis()
            val addedAtEpochMs = previousSameMovie?.addedAtEpochMs?.takeIf { it > 0L } ?: nowMs
            // 播放进度保存不携带跨源检查结果：保留同一影片已有的检查信息，避免被回写覆盖。
            val latestEpisodeCount = maxOf(
                item.latestEpisodeCount.coerceAtLeast(0),
                previousSameMovie?.latestEpisodeCount?.coerceAtLeast(0) ?: 0,
            )
            val lastUpdateCheckEpochMs = maxOf(
                item.lastUpdateCheckEpochMs.coerceAtLeast(0L),
                previousSameMovie?.lastUpdateCheckEpochMs?.coerceAtLeast(0L) ?: 0L,
            )
            val updatedItem = item.copy(
                durationMs = durationMs,
                updatedAtEpochMs = nowMs,
                addedAtEpochMs = addedAtEpochMs,
                latestEpisodeCount = latestEpisodeCount,
                lastUpdateCheckEpochMs = lastUpdateCheckEpochMs,
            )
            val updated = buildList {
                add(updatedItem)
                history
                    .filterNot { it.apiLineId == item.apiLineId && it.movieId == item.movieId }
                    .forEach(::add)
            }.take(maxItems.coerceAtLeast(1))
            check(prefs.edit().putString(KEY_ITEMS, json.encodeToString(updated)).commit()) {
                "Unable to save watch history"
            }
            updated
        }
    }

    override suspend fun updateEpisodeCheck(
        apiLineId: String,
        movieId: Int,
        latestEpisodeCount: Int,
        checkedAtEpochMs: Long,
    ): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = readHistory() ?: return@withLock emptyList()
            val index = history.indexOfFirst { it.apiLineId == apiLineId && it.movieId == movieId }
            if (index < 0) return@withLock history
            val current = history[index]
            val mergedLatest = maxOf(
                current.latestEpisodeCount.coerceAtLeast(0),
                latestEpisodeCount.coerceAtLeast(0),
            )
            val mergedCheckedAt = maxOf(
                current.lastUpdateCheckEpochMs.coerceAtLeast(0L),
                checkedAtEpochMs.coerceAtLeast(0L),
            )
            if (mergedLatest == current.latestEpisodeCount && mergedCheckedAt == current.lastUpdateCheckEpochMs) {
                return@withLock history
            }
            val updated = history.toMutableList().also {
                it[index] = current.copy(
                    latestEpisodeCount = mergedLatest,
                    lastUpdateCheckEpochMs = mergedCheckedAt,
                )
            }
            check(prefs.edit().putString(KEY_ITEMS, json.encodeToString(updated)).commit()) {
                "Unable to update watch history episode check"
            }
            updated
        }
    }

    override suspend fun clearHistory(): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(prefs.edit().remove(KEY_ITEMS).commit()) { "Unable to clear watch history" }
            emptyList()
        }
    }

    override suspend fun removeHistoryItem(
        apiLineId: String,
        movieId: Int,
    ): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = readHistory() ?: return@withLock emptyList()
            val updated = history.filterNot {
                it.apiLineId == apiLineId && it.movieId == movieId
            }
            check(prefs.edit().putString(KEY_ITEMS, json.encodeToString(updated)).commit()) {
                "Unable to remove watch history item"
            }
            updated
        }
    }

    /**
     * 读取历史。返回 null 表示「存量数据存在但解码失败」（数据损坏/字段变更），
     * 调用方必须拒绝用空表覆盖，避免整段历史被误清空。
     */
    private fun readHistory(): List<WatchHistoryItem>? {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<WatchHistoryItem>>(raw)
        }.getOrNull()
            ?.filterNot {
                isBlockedContent(
                    it.movieName,
                    it.typeName,
                    it.remarks,
                    it.episodeTitle,
                )
            }
            ?.sortedByDescending { it.updatedAtEpochMs }
    }

    private companion object {
        const val KEY_ITEMS = "items"
    }
}
