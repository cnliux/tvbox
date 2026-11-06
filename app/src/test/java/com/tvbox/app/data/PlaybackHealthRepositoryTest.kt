package com.tvbox.app.data

import com.tvbox.app.domain.PlaybackIssueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class PlaybackHealthRepositoryTest {

    @Test
    fun recordIssueAndSuccess_updateCountsAndTypes() = runBlocking {
        val store = InMemoryPlaybackHealthStore()
        val repo = DefaultPlaybackHealthRepository(store, nowMs = { 1_000L })

        repo.recordIssue("k1", PlaybackIssueType.Error, nowMs = 1_000L)
        repo.recordIssue("k1", PlaybackIssueType.SlowBuffer, nowMs = 1_100L)
        val snapshot = repo.recordSuccess("k1", nowMs = 1_200L)
        val entry = snapshot.entries.getValue("k1")

        assertEquals(1, entry.failureCount)
        assertEquals(1, entry.slowBufferCount)
        assertEquals(1, entry.successCount)
        assertEquals(1_000L, entry.lastFailureAtMs)
        assertEquals(1_100L, entry.lastSlowBufferAtMs)
        assertEquals(1_200L, entry.lastSuccessAtMs)
    }

    @Test
    fun clear_removesPersistedEntries() = runBlocking {
        val store = InMemoryPlaybackHealthStore()
        val repo = DefaultPlaybackHealthRepository(store, nowMs = { 1_000L })
        repo.recordSuccess("k1", 1_000L)
        assertTrue(store.read().isNotEmpty())

        val cleared = repo.clear()
        assertTrue(cleared.entries.isEmpty())
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun concurrentRecordIssue_doesNotLoseCounts() = runBlocking {
        val store = InMemoryPlaybackHealthStore()
        val repo = DefaultPlaybackHealthRepository(store, nowMs = { 10_000L })
        val n = 60
        val jobs = (1..n).map { i ->
            async(Dispatchers.Default) {
                repo.recordIssue("same", PlaybackIssueType.Error, nowMs = 10_000L + i)
            }
        }
        jobs.awaitAll()
        val snapshot = repo.getSnapshot()
        assertEquals(n, snapshot.entries.getValue("same").failureCount)
    }
}

/** JVM 测试用：线程安全内存存储，便于并发断言。 */
private class InMemoryPlaybackHealthStore : PlaybackHealthStore {
    private val lock = Any()
    private var data: Map<String, StoredPlaybackHealthEntry> = emptyMap()
    val writes = ConcurrentLinkedQueue<Int>()

    override fun read(): Map<String, StoredPlaybackHealthEntry> = synchronized(lock) { data }

    override fun write(entries: Collection<StoredPlaybackHealthEntry>) {
        synchronized(lock) {
            data = entries.associateBy { it.key }
            writes.add(data.size)
        }
    }

    override fun clear() {
        synchronized(lock) {
            data = emptyMap()
            writes.add(0)
        }
    }
}
