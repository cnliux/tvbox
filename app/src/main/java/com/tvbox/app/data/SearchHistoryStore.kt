package com.tvbox.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 搜索记录：已提交的关键词，去重、最新在前，最多保留 [MAX_SEARCH_HISTORY_ITEMS] 条。
 * 使用 SharedPreferences（与 HistoryRepository 同模式），进程内 mutex 保护读写。
 */
class SearchHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val PREFS_NAME = "search_history"
        const val KEY_ITEMS = "items_json"
        const val MAX_SEARCH_HISTORY_ITEMS = 20
    }

    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val raw = prefs.getString(KEY_ITEMS, null) ?: return@withContext emptyList()
                json.decodeFromString<List<String>>(raw)
            }.getOrDefault(emptyList())
        }
    }

    suspend fun add(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val existing = runCatching {
                    prefs.getString(KEY_ITEMS, null)?.let {
                        json.decodeFromString<List<String>>(it)
                    }
                }.getOrNull() ?: emptyList()
                val updated = (listOf(trimmed) + existing.filter { it != trimmed })
                    .take(MAX_SEARCH_HISTORY_ITEMS)
                prefs.edit()
                    .putString(KEY_ITEMS, json.encodeToString(updated))
                    .apply()
            }
        }
    }

    suspend fun remove(keyword: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val existing = runCatching {
                    prefs.getString(KEY_ITEMS, null)?.let {
                        json.decodeFromString<List<String>>(it)
                    }
                }.getOrNull() ?: emptyList()
                val updated = existing.filter { it != keyword }
                prefs.edit()
                    .putString(KEY_ITEMS, json.encodeToString(updated))
                    .apply()
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock { prefs.edit().remove(KEY_ITEMS).apply() }
        }
    }
}
