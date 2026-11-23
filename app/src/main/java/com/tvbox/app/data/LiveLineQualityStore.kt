package com.tvbox.app.data

import android.content.Context
import android.util.Log
import com.tvbox.app.domain.LineQualityRecord
import com.tvbox.app.domain.recordLineStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 直播线路级起播质量的本地持久化。
 *
 * 与 [LiveDomainScoreStore] 互补：后者按域名跨频道统计，这里按「频道键 + 线路下标」
 * 记录每条线路的起播耗时与成败，用于进台时从「同一频道历史最快」的线路开始。
 *
 * 只在有正分记录时才影响选线，全部线路都失败时仍保持源内原顺序，绝不硬剔除线路。
 */
class LiveLineQualityStore(context: Context) {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cache: Map<String, LineQualityRecord>? = null

    @Synchronized
    fun snapshot(): Map<String, LineQualityRecord> = cache ?: load()

    /** 记录一次起播结果；内存同步更新，落盘异步 apply，不阻塞播放回调。 */
    @Synchronized
    fun record(
        lineKey: String,
        startDurationMs: Long?,
        success: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (lineKey.isBlank()) return
        val updated = recordLineStart(snapshot(), lineKey, startDurationMs, success, nowMs)
        cache = updated
        runCatching {
            prefs.edit()
                .putString(KEY_ENTRIES, json.encodeToString(updated.values.map { it.toStored() }))
                .apply()
        }.onFailure { Log.w(TAG, "线路质量表写入失败：${it.message}") }
    }

    @Synchronized
    fun clear() {
        cache = emptyMap()
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    @Synchronized
    private fun load(): Map<String, LineQualityRecord> {
        val cached = cache
        if (cached != null) return cached
        val raw = prefs.getString(KEY_ENTRIES, null)
        val parsed = if (raw.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                json.decodeFromString<List<StoredLineQuality>>(raw)
                    .mapNotNull { it.toDomain() }
                    .associateBy { it.lineKey }
            }.getOrElse { error ->
                Log.w(TAG, "线路质量表解码失败，已回退为空表：${error.message}")
                emptyMap()
            }
        }
        cache = parsed
        return parsed
    }

    @Serializable
    private data class StoredLineQuality(
        val lineKey: String,
        val lastStartDurationMs: Long? = null,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val lastAttemptAtMs: Long = 0L,
    ) {
        fun toDomain(): LineQualityRecord? =
            lineKey.takeIf { it.isNotBlank() }?.let {
                LineQualityRecord(
                    lineKey = it,
                    lastStartDurationMs = lastStartDurationMs,
                    successCount = successCount,
                    failureCount = failureCount,
                    lastAttemptAtMs = lastAttemptAtMs,
                )
            }
    }

    private fun LineQualityRecord.toStored() = StoredLineQuality(
        lineKey = lineKey,
        lastStartDurationMs = lastStartDurationMs,
        successCount = successCount,
        failureCount = failureCount,
        lastAttemptAtMs = lastAttemptAtMs,
    )

    private companion object {
        const val PREFS_NAME = "live_line_quality"
        const val KEY_ENTRIES = "entries_v1"
        const val TAG = "TVBoxLiveLineQuality"
    }
}
