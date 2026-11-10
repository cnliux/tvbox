package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLineQualityTest {
    private val nowMs = 100_000L

    private fun lines(count: Int) = List(count) { index ->
        LiveChannelLine(name = "线路${index + 1}", url = "https://cdn.test/$index.m3u8")
    }

    @Test
    fun noRecordsKeepsSourceOrder() {
        assertEquals(listOf(0, 1, 2), rankLinesByQuality(lines(3), emptyMap(), "央视/综合", nowMs))
        assertEquals(listOf(0), rankLinesByQuality(lines(1), emptyMap(), "央视/综合", nowMs))
    }

    @Test
    fun fastSuccessfulLineIsRankedFirst() {
        var records = recordLineStart(emptyMap(), lineQualityKey("央视/综合", 0), 6_000L, true, nowMs)
        records = recordLineStart(records, lineQualityKey("央视/综合", 1), 800L, true, nowMs)
        records = recordLineStart(records, lineQualityKey("央视/综合", 2), 2_000L, true, nowMs)

        val ranked = rankLinesByQuality(lines(3), records, "央视/综合", nowMs)
        assertEquals(1, ranked.first())
    }

    @Test
    fun failedLineFallsBelowNeverTriedLine() {
        var records = recordLineStart(emptyMap(), lineQualityKey("央视/综合", 0), null, false, nowMs)
        records = recordLineStart(records, lineQualityKey("央视/综合", 0), null, false, nowMs + 1L)
        val ranked = rankLinesByQuality(lines(3), records, "央视/综合", nowMs + 2L)
        assertEquals(1, ranked.first())
        assertEquals(0, ranked.last())
    }

    @Test
    fun failureDoesNotOverwritePreviousStartDuration() {
        val success = recordLineStart(emptyMap(), "k", 1_200L, true, nowMs)
        val failed = recordLineStart(success, "k", null, false, nowMs + 1L)
        val record = failed.getValue("k")
        assertEquals(1_200L, record.lastStartDurationMs)
        assertEquals(1, record.successCount)
        assertEquals(1, record.failureCount)
        assertEquals(nowMs + 1L, record.lastAttemptAtMs)
    }

    @Test
    fun capacityEvictionKeepsMostRecentRecords() {
        var records = emptyMap<String, LineQualityRecord>()
        for (index in 0 until 4) {
            records = recordLineStart(records, "k$index", 1_000L, true, nowMs + index, maxEntries = 2)
        }
        assertEquals(2, records.size)
        assertTrue(records.containsKey("k3"))
        assertTrue(records.containsKey("k2"))
    }

    @Test
    fun emptyLineKeyIsIgnored() {
        val records = recordLineStart(emptyMap(), "", null, false, nowMs)
        assertTrue(records.isEmpty())
        assertNull(records[""])
    }
}
