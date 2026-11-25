package com.tvbox.app.data

import com.tvbox.app.domain.PlaybackEvidence
import com.tvbox.app.domain.PlaybackEvidenceEvent
import com.tvbox.app.domain.PlaybackIssueType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新字段持久化与证据环形日志的仓库层单测。 */
class PlaybackHealthEvidenceTest {

    @Test
    fun latencyAndStutterSamplesArePersisted() = runBlocking {
        val store = InMemoryStore()
        val repo = DefaultPlaybackHealthRepository(store, nowMs = { 1_000L })

        repo.recordSuccessWithLatency("k", 640L, 1_000L)
        repo.recordIssue("k", PlaybackIssueType.SlowBuffer, 1_100L)

        val stored = store.read().getValue("k")
        assertEquals(listOf(640L), stored.successLatencyMs)
        assertEquals(1, stored.stutterSampleCount)
        assertEquals(0.5f, repo.getSnapshot().entries.getValue("k").stutterRate, 0.0001f)
    }

    @Test
    fun evidenceIsNewestFirstBoundedAndCleared() = runBlocking {
        val repo = DefaultPlaybackHealthRepository(InMemoryStore(), nowMs = { 1_000L })
        repeat(55) { index ->
            repo.recordEvidence(
                PlaybackEvidence(index.toLong(), "line", PlaybackEvidenceEvent.Seek),
            )
        }

        val evidence = repo.getEvidence()
        assertEquals(50, evidence.size)
        assertEquals(54L, evidence.first().atMs)
        assertEquals(3, repo.getEvidence(3).size)

        repo.clear()
        assertEquals(0, repo.getEvidence().size)
    }

    private class InMemoryStore : PlaybackHealthStore {
        private var data: Map<String, StoredPlaybackHealthEntry> = emptyMap()

        override fun read(): Map<String, StoredPlaybackHealthEntry> = data

        override fun write(entries: Collection<StoredPlaybackHealthEntry>) {
            data = entries.associateBy { it.key }
        }

        override fun clear() {
            data = emptyMap()
        }
    }
}
