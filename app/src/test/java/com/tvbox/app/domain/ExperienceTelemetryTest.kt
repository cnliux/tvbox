package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3-19：体验指标采集层的 JVM 单测（重点验证不泄露自由文本）。 */
class ExperienceTelemetryTest {

    @Test
    fun ringBufferKeepsOnlyNewestTwoHundred() {
        val buffer = TelemetryBuffer(capacity = 3)
        repeat(5) { buffer.record(MetricTag.TtfpP50, it, it.toLong()) }
        assertEquals(listOf(2, 3, 4), buffer.snapshot().map { it.v })
    }

    @Test
    fun disabledClearsBufferAndStopsRecording() {
        ExperienceTelemetry.enabled = true
        ExperienceTelemetry.clear()
        ExperienceTelemetry.record(MetricTag.TtfpP95, 900, 1L)
        assertEquals(1, ExperienceTelemetry.snapshot().size)

        ExperienceTelemetry.enabled = false
        assertTrue(ExperienceTelemetry.snapshot().isEmpty())
        ExperienceTelemetry.record(MetricTag.TtfpP95, 900, 2L)
        assertTrue(ExperienceTelemetry.snapshot().isEmpty())
        assertEquals("", ExperienceTelemetry.encode())

        ExperienceTelemetry.enabled = true
        ExperienceTelemetry.clear()
    }

    @Test
    fun encodeOnlyContainsEnumKeysAndNumbers() {
        val text = encodeTicks(
            listOf(
                Tick(1L, MetricTag.TtfpP50.key, 850),
                Tick(2L, MetricTag.MemoryTier.key, 1, MetricTag.Dim.Critical.value),
            ),
        )
        assertTrue(text.contains("\"k\":\"ttfp_p50\""))
        assertTrue(text.contains("\"tag\":\"critical\""))
        assertFalse(text.contains("流浪地球"))
        assertFalse(text.contains("http://"))
    }

    @Test
    fun unknownFreeTextIsDroppedNotEchoed() {
        val text = encodeTicks(listOf(Tick(1L, "流浪地球2", 1, "http://192.168.1.1/secret")))
        assertTrue(text.contains("\"k\":\"\""))
        assertTrue(text.contains("\"tag\":\"\""))
        assertFalse(text.contains("流浪地球"))
        assertFalse(text.contains("192.168"))
    }

    @Test
    fun allSixCoreMetricsAreRepresentable() {
        val names = MetricTag.ALL.map { it.key }
        assertTrue(names.containsAll(
            listOf("ttfp_p50", "ttfp_p95", "rebuffer_ratio", "seek_p95_ok",
                "source_dead_rate", "remote_key_latency_p95", "oom_kill_per_1k_sess", "memory_tier"),
        ))
        assertEquals(8, names.size)
    }
}
