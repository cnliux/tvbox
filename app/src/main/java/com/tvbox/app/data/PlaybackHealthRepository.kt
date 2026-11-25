package com.tvbox.app.data

import android.content.Context
import android.util.Log
import com.tvbox.app.domain.PlaybackEvidence
import com.tvbox.app.domain.PlaybackHealthEntry
import com.tvbox.app.domain.PlaybackHealthSnapshot
import com.tvbox.app.domain.PlaybackIssueType
import com.tvbox.app.domain.appendEvidence
import com.tvbox.app.domain.appendSuccessLatency
import com.tvbox.app.domain.prunePlaybackHealthEntries
import com.tvbox.app.domain.recentEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 线路健康记录的存储抽象（JVM 可替换为内存/假实现做单测）。 */
interface PlaybackHealthStore {
    fun read(): Map<String, StoredPlaybackHealthEntry>
    fun write(entries: Collection<StoredPlaybackHealthEntry>)
    fun clear()
}

@Serializable
data class StoredPlaybackHealthEntry(
    val key: String,
    val lastFailureAtMs: Long = 0L,
    val lastSlowBufferAtMs: Long = 0L,
    val lastSuccessAtMs: Long = 0L,
    val failureCount: Int = 0,
    val slowBufferCount: Int = 0,
    val successCount: Int = 0,
    /** 新增字段均有默认值，旧 JSON 解码后自动得到空样本。 */
    val successLatencyMs: List<Long> = emptyList(),
    val stutterSampleCount: Int = 0,
) {
    val latestActivityAtMs: Long
        get() = maxOf(lastFailureAtMs, lastSlowBufferAtMs, lastSuccessAtMs)
}

fun PlaybackHealthEntry.toStored(): StoredPlaybackHealthEntry = StoredPlaybackHealthEntry(
    key = key,
    lastFailureAtMs = lastFailureAtMs,
    lastSlowBufferAtMs = lastSlowBufferAtMs,
    lastSuccessAtMs = lastSuccessAtMs,
    failureCount = failureCount,
    slowBufferCount = slowBufferCount,
    successCount = successCount,
    successLatencyMs = successLatencyMs,
    stutterSampleCount = stutterSampleCount,
)

fun StoredPlaybackHealthEntry.toDomain(): PlaybackHealthEntry = PlaybackHealthEntry(
    key = key,
    lastFailureAtMs = lastFailureAtMs,
    lastSlowBufferAtMs = lastSlowBufferAtMs,
    lastSuccessAtMs = lastSuccessAtMs,
    failureCount = failureCount,
    slowBufferCount = slowBufferCount,
    successCount = successCount,
    successLatencyMs = successLatencyMs,
    stutterSampleCount = stutterSampleCount,
)

class SharedPreferencesPlaybackHealthStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : PlaybackHealthStore {
    // lazy：getSharedPreferences 首次调用会同步解析 XML，避免在启动路径（ViewModel 工厂）上读盘。
    private val prefs by lazy { context.applicationContext.getSharedPreferences("playback_health", Context.MODE_PRIVATE) }

    override fun read(): Map<String, StoredPlaybackHealthEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<List<StoredPlaybackHealthEntry>>(raw)
                .filter { it.key.isNotBlank() }
                .associateBy { it.key }
        }.getOrElse { error ->
            Log.w("TVBoxPlaybackHealth", "线路健康记录解码失败，已回退为空表：${error.message}")
            emptyMap()
        }
    }

    override fun write(entries: Collection<StoredPlaybackHealthEntry>) {
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(entries.toList())).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}

interface PlaybackHealthRepository {
    suspend fun getSnapshot(): PlaybackHealthSnapshot
    suspend fun recordIssue(key: String, issueType: PlaybackIssueType, nowMs: Long): PlaybackHealthSnapshot
    suspend fun recordSuccess(key: String, nowMs: Long): PlaybackHealthSnapshot
    suspend fun clear(): PlaybackHealthSnapshot

    /**
     * 带首帧延迟的成功记录。默认回退到旧接口，避免破坏已有的匿名实现；
     * DefaultPlaybackHealthRepository 会真正写入 [PlaybackHealthEntry.successLatencyMs]。
     */
    suspend fun recordSuccessWithLatency(
        key: String,
        latencyMs: Long,
        nowMs: Long,
    ): PlaybackHealthSnapshot = recordSuccess(key, nowMs)

    /** 追加一条证据事件（最新在前，环形上限 50）；默认实现为空操作。 */
    suspend fun recordEvidence(evidence: PlaybackEvidence): List<PlaybackEvidence> = emptyList()

    /** 读取最近证据，供播放管家面板展示。 */
    suspend fun getEvidence(limit: Int = 50): List<PlaybackEvidence> = emptyList()
}

/**
 * 默认实现：用 [PlaybackHealthStore] 持久化，读-改-写由 [Mutex] 串行化。
 * 构造器注入 store/nowMs 便于 JVM 单测（无需 Android Context）。
 */
class DefaultPlaybackHealthRepository(
    private val store: PlaybackHealthStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : PlaybackHealthRepository {
    private val mutex = Mutex()
    /** 上次写入的条目指纹：用于 getSnapshot 无变化时跳过全量重写。 */
    private var lastWrittenEntries: List<StoredPlaybackHealthEntry> = emptyList()
    /** 证据日志只放内存：环形上限 50 条，不写 Dropbox、不落盘。 */
    private var evidenceLog: List<PlaybackEvidence> = emptyList()

    /** 有变化才写盘，并同步更新指纹；避免读快照也触发全量 I/O。 */
    private fun writeIfChanged(stored: List<StoredPlaybackHealthEntry>) {
        if (stored != lastWrittenEntries) {
            store.write(stored)
            lastWrittenEntries = stored
        }
    }

    override suspend fun getSnapshot(): PlaybackHealthSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = nowMs()
            val entries = readPruned(now)
            writeIfChanged(entries.map { it.toStored() })
            entries.toSnapshot()
        }
    }

    override suspend fun recordIssue(
        key: String,
        issueType: PlaybackIssueType,
        nowMs: Long,
    ): PlaybackHealthSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPruned(nowMs).associateBy { it.key }.toMutableMap()
            val entry = current[key] ?: PlaybackHealthEntry(key = key)
            current[key] = when (issueType) {
                PlaybackIssueType.Error -> entry.copy(
                    lastFailureAtMs = nowMs,
                    failureCount = entry.failureCount + 1,
                )
                PlaybackIssueType.SlowBuffer -> entry.copy(
                    lastSlowBufferAtMs = nowMs,
                    slowBufferCount = entry.slowBufferCount + 1,
                    stutterSampleCount = entry.stutterSampleCount + 1,
                )
            }
            val entries = prunePlaybackHealthEntries(current.values, nowMs)
            writeIfChanged(entries.map { it.toStored() })
            entries.toSnapshot()
        }
    }

    override suspend fun recordSuccess(key: String, nowMs: Long): PlaybackHealthSnapshot =
        recordSuccessInternal(key = key, latencyMs = null, nowMs = nowMs)

    override suspend fun recordSuccessWithLatency(
        key: String,
        latencyMs: Long,
        nowMs: Long,
    ): PlaybackHealthSnapshot = recordSuccessInternal(key = key, latencyMs = latencyMs, nowMs = nowMs)

    private suspend fun recordSuccessInternal(
        key: String,
        latencyMs: Long?,
        nowMs: Long,
    ): PlaybackHealthSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPruned(nowMs).associateBy { it.key }.toMutableMap()
            var entry = current[key] ?: PlaybackHealthEntry(key = key)
            entry = entry.copy(lastSuccessAtMs = nowMs, successCount = entry.successCount + 1)
            if (latencyMs != null) {
                entry = appendSuccessLatency(entry, latencyMs)
            }
            current[key] = entry
            val entries = prunePlaybackHealthEntries(current.values, nowMs)
            writeIfChanged(entries.map { it.toStored() })
            entries.toSnapshot()
        }
    }

    override suspend fun recordEvidence(evidence: PlaybackEvidence): List<PlaybackEvidence> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                evidenceLog = appendEvidence(evidenceLog, evidence)
                evidenceLog
            }
        }

    override suspend fun getEvidence(limit: Int): List<PlaybackEvidence> = withContext(Dispatchers.IO) {
        mutex.withLock { recentEvidence(evidenceLog, limit) }
    }

    override suspend fun clear(): PlaybackHealthSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.clear()
            lastWrittenEntries = emptyList()
            evidenceLog = emptyList()
            PlaybackHealthSnapshot()
        }
    }

    private fun readPruned(nowMs: Long): List<PlaybackHealthEntry> =
        prunePlaybackHealthEntries(store.read().values.map { it.toDomain() }, nowMs)

    private fun Collection<PlaybackHealthEntry>.toSnapshot(): PlaybackHealthSnapshot =
        PlaybackHealthSnapshot(associateBy { it.key })
}

/** Android 入口：SharedPreferences 持久化。 */
class SharedPlaybackHealthRepository(context: Context) :
    PlaybackHealthRepository by DefaultPlaybackHealthRepository(
        SharedPreferencesPlaybackHealthStore(context),
    )
