package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.PlaybackHealthEntry
import com.tvbox.app.domain.PlaybackHealthSnapshot
import com.tvbox.app.domain.PlaybackIssueType
import com.tvbox.app.domain.prunePlaybackHealthEntries
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
)

fun StoredPlaybackHealthEntry.toDomain(): PlaybackHealthEntry = PlaybackHealthEntry(
    key = key,
    lastFailureAtMs = lastFailureAtMs,
    lastSlowBufferAtMs = lastSlowBufferAtMs,
    lastSuccessAtMs = lastSuccessAtMs,
    failureCount = failureCount,
    slowBufferCount = slowBufferCount,
    successCount = successCount,
)

class SharedPreferencesPlaybackHealthStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : PlaybackHealthStore {
    private val prefs = context.applicationContext.getSharedPreferences("playback_health", Context.MODE_PRIVATE)

    override fun read(): Map<String, StoredPlaybackHealthEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<List<StoredPlaybackHealthEntry>>(raw)
                .filter { it.key.isNotBlank() }
                .associateBy { it.key }
        }.getOrDefault(emptyMap())
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

    override suspend fun getSnapshot(): PlaybackHealthSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = nowMs()
            val entries = readPruned(now)
            store.write(entries.map { it.toStored() })
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
                )
            }
            val entries = prunePlaybackHealthEntries(current.values, nowMs)
            store.write(entries.map { it.toStored() })
            entries.toSnapshot()
        }
    }

    override suspend fun recordSuccess(key: String, nowMs: Long): PlaybackHealthSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPruned(nowMs).associateBy { it.key }.toMutableMap()
            val entry = current[key] ?: PlaybackHealthEntry(key = key)
            current[key] = entry.copy(lastSuccessAtMs = nowMs, successCount = entry.successCount + 1)
            val entries = prunePlaybackHealthEntries(current.values, nowMs)
            store.write(entries.map { it.toStored() })
            entries.toSnapshot()
        }
    }

    override suspend fun clear(): PlaybackHealthSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.clear()
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
