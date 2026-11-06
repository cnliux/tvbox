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

interface HistoryRepository {
    suspend fun getHistory(): List<WatchHistoryItem>
    suspend fun saveProgress(
        item: WatchHistoryItem,
        maxItems: Int = MAX_HISTORY_ITEMS,
    ): List<WatchHistoryItem>
    suspend fun clearHistory(): List<WatchHistoryItem>
}

class SharedHistoryRepository(context: Context) : HistoryRepository {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun getHistory(): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock { readHistory() }
    }

    override suspend fun saveProgress(
        item: WatchHistoryItem,
        maxItems: Int,
    ): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = readHistory()
            // 播放器尚未 READY 时保存会带 duration=0；若直接覆盖，历史进度条会归零且
            // 提前退出时永久丢失总时长。此时沿用同一集已有记录的时长。
            val previous = history.firstOrNull {
                it.apiLineId == item.apiLineId && it.movieId == item.movieId &&
                    (it.episodeIndex == item.episodeIndex || it.episodeUrl == item.episodeUrl)
            }
            val durationMs = if (item.durationMs > 0L) item.durationMs else previous?.durationMs ?: 0L
            val updatedItem = item.copy(
                durationMs = durationMs,
                updatedAtEpochMs = System.currentTimeMillis(),
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

    override suspend fun clearHistory(): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(prefs.edit().remove(KEY_ITEMS).commit()) { "Unable to clear watch history" }
            emptyList()
        }
    }

    private fun readHistory(): List<WatchHistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<WatchHistoryItem>>(raw)
        }.getOrDefault(emptyList())
            .filterNot {
                isBlockedContent(
                    it.movieName,
                    it.typeName,
                    it.remarks,
                    it.episodeTitle,
                )
            }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    private companion object {
        const val KEY_ITEMS = "items"
    }
}
