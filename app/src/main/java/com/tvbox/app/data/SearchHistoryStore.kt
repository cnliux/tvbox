package com.tvbox.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 搜索记录：已提交的关键词，去重、最新在前，最多保留 [MAX_SEARCH_HISTORY_ITEMS] 条。
 *
 * S1-06 增强：
 * - 每条记录携带写入时间戳（[SearchEntry.createdAtEpochMs]）。
 * - [load()] 自动过滤超过 [ttlDays] 天的过期条目；ttlDays <= 0 表示永不清理。
 * - 当 [SearchHistoryStore.isVisitorMode] 为 true 时 [add()] 静默跳过。
 * - 新增 [clearExpired()] 供定时任务调用。
 */
class SearchHistoryStore internal constructor(
    private val prefs: android.content.SharedPreferences,
    private val ttlDays: Int = DEFAULT_TTL_DAYS,
) {
    /**
     * 公开构造器：从 Android Context 创建，使用默认 TTL（30天）。
     * 生产代码通过此入口创建；单元测试请使用内部分支构造器。
     */
    constructor(context: Context, ttlDays: Int = DEFAULT_TTL_DAYS) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ttlDays = ttlDays,
    )

    companion object {
        const val PREFS_NAME = "search_history"
        const val KEY_ITEMS = "items_json"
        const val MAX_SEARCH_HISTORY_ITEMS = 20
        /** 默认 TTL：30 天。 */
        const val DEFAULT_TTL_DAYS = 30
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val raw = prefs.getString(KEY_ITEMS, null) ?: return@withContext emptyList()
                json.decodeFromString<List<SearchEntry>>(raw)
                    .filter { it.appliesTtl(ttlDays) }
                    .map { it.keyword }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun add(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        // S1-06：访客模式/隐私模式下跳过写入。
        if (isVisitorMode) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val existing = runCatching {
                    prefs.getString(KEY_ITEMS, null)?.let {
                        json.decodeFromString<List<SearchEntry>>(it)
                    }
                }.getOrNull() ?: emptyList()
                val updated = (listOf(SearchEntry(trimmed, System.currentTimeMillis())) +
                        existing.filter { it.keyword != trimmed })
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
                        json.decodeFromString<List<SearchEntry>>(it)
                    }
                }.getOrNull() ?: emptyList()
                val updated = existing.filter { it.keyword != keyword }
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

    /**
     * 清除所有超过 TTL 的条目。
     * 供定时任务或 App 启动时调用，避免每次 load() 都扫描全量数据。
     */
    suspend fun clearExpired() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (ttlDays <= 0) return@withLock // 永不清理时直接返回
                val nowMs = System.currentTimeMillis()
                val thresholdMs = ttlDays * 86_400_000L
                val existing = runCatching {
                    prefs.getString(KEY_ITEMS, null)?.let {
                        json.decodeFromString<List<SearchEntry>>(it)
                    }
                }.getOrNull() ?: emptyList()
                val updated = existing.filter { nowMs - it.createdAtEpochMs <= thresholdMs }
                if (updated.size == existing.size) return@withLock // 没有条目过期，不写盘
                prefs.edit()
                    .putString(KEY_ITEMS, json.encodeToString(updated))
                    .apply()
            }
        }
    }

    /**
     * S1-06：访客模式标志；由 TvBoxViewModel 在切换到访客模式时设置。
     * 默认 false，重启 App 后重置为 false。
     */
    var isVisitorMode: Boolean = false
}

/**
 * 搜索历史内部存储结构：关键词 + 写入时间戳（epoch ms）。
 * JSON 序列化时使用简单形式；API 对外仍暴露纯字符串列表。
 */
@Serializable
data class SearchEntry(
    val keyword: String,
    @SerialName("ts") val createdAtEpochMs: Long,
) {
    fun appliesTtl(ttlDays: Int): Boolean {
        if (ttlDays <= 0) return true
        val ageMs = System.currentTimeMillis() - createdAtEpochMs
        val thresholdMs = ttlDays * 86_400_000L
        return ageMs <= thresholdMs
    }
}
