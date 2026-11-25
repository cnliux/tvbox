package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.DurationConfidence
import com.tvbox.app.domain.LiveDomainScore
import com.tvbox.app.domain.contributesToScore
import com.tvbox.app.domain.updateLiveDomainScores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 直播线路域名评分表的本地持久化。
 *
 * 用 SharedPreferences 单键存 JSON：条目上限 [LIVE_DOMAIN_MAX_ENTRIES]，体积可控；
 * 读取失败（旧格式/损坏）时返回空表，不阻塞播放。
 * 所有 load / read-modify-write / clear 由同一把锁串行化，避免并发播放回调互相覆盖评分。
 */
class LiveDomainScoreStore(context: Context) {
    // lazy：getSharedPreferences 首次调用会同步解析 XML，避免在启动路径（ViewModel 工厂）上读盘。
    private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var cache: Map<String, LiveDomainScore>? = null

    /**
     * T1-04：各域名最近一次测速的时长可信度。只放内存：这是会话级探测信号，
     * 重启后未记录时按可信处理（[DurationConfidence.HIGH]），保持与旧版本行为一致。
     * 所有读写与 [cache] 一样由 [Synchronized] 串行化。
     */
    private val durationConfidenceByDomain: MutableMap<String, DurationConfidence> = mutableMapOf()

    @Synchronized
    fun snapshot(): Map<String, LiveDomainScore> = cache ?: load()

    /** 记录一次播放结果；同步写内存缓存，磁盘写放到 IO 线程。 */
    @Synchronized
    fun record(domain: String, success: Boolean, nowMs: Long = System.currentTimeMillis()) {
        // 旧调用方不传可信度：按 HIGH 处理，行为与接线前保持一致。
        record(domain, success, DurationConfidence.HIGH, nowMs)
    }

    /**
     * T1-04：带时长可信度的播放结果记录 —— [contributesToScore] 的评分入参。
     * 可信度用于 [scoreFor] 对正向分打折，失败扣分不受影响。
     */
    @Synchronized
    fun record(
        domain: String,
        success: Boolean,
        confidence: DurationConfidence,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (domain.isBlank()) return
        durationConfidenceByDomain[domain.lowercase()] = confidence
        val updated = updateLiveDomainScores(snapshot(), domain, success, nowMs)
        cache = updated
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(updated.map { it.value.toStored() })).apply()
    }

    /**
     * T1-04：时长可信度加权域名分。NONE（如 chunked / 伪静态）时成功带来的正向分打折，
     * 避免把 Content-Length 猜出来的"成绩"当成真实优选依据；负分（失败历史）照常保留。
     */
    @Synchronized
    fun scoreFor(domain: String, nowMs: Long): Double {
        val key = domain.lowercase()
        val base = snapshot()[key]?.score(nowMs) ?: 0.0
        val confidence = durationConfidenceByDomain[key] ?: DurationConfidence.HIGH
        return confidenceAdjustedScore(base, confidence)
    }

    @Synchronized
    fun clear() {
        cache = emptyMap()
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    suspend fun loadAsync(): Map<String, LiveDomainScore> = withContext(Dispatchers.IO) { load() }

    @Synchronized
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
            }.getOrElse { error ->
                Log.w(TAG, "直播线路评分表解码失败，已回退为空表：${error.message}")
                emptyMap()
            }
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
        const val TAG = "TVBoxLiveDomainScore"
    }
}

/** T1-04：时长不可信时正向域名分的折扣系数；负分（失败历史）不打折扣。 */
const val UNTRUSTED_DOMAIN_SCORE_FACTOR = 0.5

/** T1-04：纯函数形式的时长可信度调分，供 [LiveDomainScoreStore.scoreFor] 与 JVM 单测共用。 */
fun confidenceAdjustedScore(base: Double, confidence: DurationConfidence): Double =
    if (base <= 0.0 || contributesToScore(confidence)) base else base * UNTRUSTED_DOMAIN_SCORE_FACTOR
