package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.LiveDomainScore
import com.tvbox.app.domain.LIVE_DOMAIN_MAX_ENTRIES
import com.tvbox.app.domain.updateLiveDomainScores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 直播线路域名评分表的本地持久化。
 *
 * 用 SharedPreferences 单键存 JSON：条目上限 [LIVE_DOMAIN_MAX_ENTRIES]，体积可控；
 * 读取失败（旧格式/损坏）时返回空表，不阻塞播放。
 */
class LiveDomainScoreStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cache: Map<String, LiveDomainScore>? = null

    fun snapshot(): Map<String, LiveDomainScore> = cache ?: load()

    /** 记录一次播放结果；同步写内存缓存，磁盘写放到 IO 线程。 */
    fun record(domain: String, success: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (domain.isBlank()) return
        val updated = updateLiveDomainScores(snapshot(), domain, success, nowMs)
        cache = updated
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(updated.map { it.value.toStored() })).apply()
    }

    fun clear() {
        cache = emptyMap()
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    suspend fun loadAsync(): Map<String, LiveDomainScore> = withContext(Dispatchers.IO) { load() }

    private fun load(): Map<String, LiveDomainScore> {
        val cached = cache
        if (cached != null) return cached
        val raw = prefs.getString(KEY_ENTRIES, null)
        val parsed = if (raw.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                json.decodeFromString<List<StoredLiveDomainScore>>(raw)
                    .mapNotNull { it.toDomain() }
                    .associateBy { it.domain }
            }.getOrDefault(emptyMap())
        }
        cache = parsed
        return parsed
    }

    @Serializable
    private data class StoredLiveDomainScore(
        val domain: String,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val lastSuccessAtMs: Long = 0L,
        val lastFailureAtMs: Long = 0L,
        val lastActivityAtMs: Long = 0L,
    ) {
        fun toDomain(): LiveDomainScore? =
            domain.takeIf { it.isNotBlank() }?.let {
                LiveDomainScore(
                    domain = it,
                    successCount = successCount,
                    failureCount = failureCount,
                    lastSuccessAtMs = lastSuccessAtMs,
                    lastFailureAtMs = lastFailureAtMs,
                    lastActivityAtMs = lastActivityAtMs,
                )
            }
    }

    private fun LiveDomainScore.toStored() = StoredLiveDomainScore(
        domain = domain,
        successCount = successCount,
        failureCount = failureCount,
        lastSuccessAtMs = lastSuccessAtMs,
        lastFailureAtMs = lastFailureAtMs,
        lastActivityAtMs = lastActivityAtMs,
    )

    private companion object {
        const val PREFS_NAME = "live_domain_scores"
        const val KEY_ENTRIES = "entries_v1"
    }
}
