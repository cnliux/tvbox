package com.tvbox.app.data.network

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-07：单一重试仲裁器的 JVM 单测，不发起任何网络请求。 */
class RetryArbiterTest {

    private class FakeClock(var now: Long) {
        val nowMs: () -> Long = { now }
    }

    @Test
    fun normalizeHostCoversUrlUserinfoPortAndIpv6() {
        assertEquals("example.com", RetryArbiter.normalizeHost("https://user:pw@EXAMPLE.COM:8443/a?b=1"))
        assertEquals("example.com", RetryArbiter.normalizeHost("example.com."))
        assertEquals("2001:db8::1", RetryArbiter.normalizeHost("http://[2001:DB8::1]:8080/"))
        assertEquals("", RetryArbiter.normalizeHost(" "))
        assertEquals("", RetryArbiter.normalizeHost("http://[broken"))
    }

    @Test
    fun probeBudgetIsTwoPerHostPerWindow() {
        val clock = FakeClock(1_000L)
        val arbiter = RetryArbiter(nowMs = clock.nowMs)
        assertTrue(arbiter.permit("https://a.example.com:8080/x", RetryKind.PROBE))
        assertTrue(arbiter.permit("http://a.example.com/y", RetryKind.PROBE))
        assertFalse(arbiter.permit("a.example.com", RetryKind.PROBE))
        assertEquals(2, arbiter.used("a.example.com", RetryKind.PROBE))
    }

    @Test
    fun differentKindsAreCountedSeparately() {
        val arbiter = RetryArbiter(nowMs = { 0L })
        repeat(2) { assertTrue(arbiter.permit("host", RetryKind.PROBE)) }
        assertFalse(arbiter.permit("host", RetryKind.PROBE))
        assertTrue(arbiter.permit("host", RetryKind.PLAYBACK))
        assertTrue(arbiter.permit("host", RetryKind.OTA))
    }

    @Test
    fun playbackBudgetIsThreeAndOtaIsTwo() {
        val arbiter = RetryArbiter(nowMs = { 0L })
        repeat(3) { assertTrue(arbiter.permit("host", RetryKind.PLAYBACK)) }
        assertFalse(arbiter.permit("host", RetryKind.PLAYBACK))
        repeat(2) { assertTrue(arbiter.permit("host", RetryKind.OTA)) }
        assertFalse(arbiter.permit("host", RetryKind.OTA))
    }

    @Test
    fun windowRollsOverAfterSixtySeconds() {
        val clock = FakeClock(1_000L)
        val arbiter = RetryArbiter(nowMs = clock.nowMs, windowMs = 60_000L)
        repeat(2) { assertTrue(arbiter.permit("host", RetryKind.PROBE)) }
        assertFalse(arbiter.permit("host", RetryKind.PROBE))
        clock.now += 60_001L
        assertTrue(arbiter.permit("host", RetryKind.PROBE))
        assertEquals(1, arbiter.used("host", RetryKind.PROBE))
    }

    @Test
    fun backwardClockAlsoResetsWindow() {
        val clock = FakeClock(5_000L)
        val arbiter = RetryArbiter(nowMs = clock.nowMs)
        assertTrue(arbiter.permit("host", RetryKind.PROBE))
        clock.now = 1_000L // 用户改表 / 进程时钟回拨
        assertTrue(arbiter.permit("host", RetryKind.PROBE))
        assertEquals(1, arbiter.used("host", RetryKind.PROBE))
    }

    @Test
    fun backoffIsExponentialWithFullJitterAndClamped() {
        val fixed = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        } // 固定随机源，抖动取 0
        val arbiter = RetryArbiter(random = fixed)
        assertEquals(250L, arbiter.backoffMs(0))
        assertEquals(500L, arbiter.backoffMs(1))
        assertEquals(1_000L, arbiter.backoffMs(2))
        assertEquals(4_000L, arbiter.backoffMs(4))
        assertEquals(4_000L, arbiter.backoffMs(99))

        val jittered = RetryArbiter(random = Random(0))
        val sample = (0 until 20).map { jittered.backoffMs(1) }
        assertTrue(sample.all { it in 500L..899L })
        assertTrue(sample.distinct().size > 1)
    }

    @Test
    fun resetClearsEveryHost() {
        val arbiter = RetryArbiter(nowMs = { 0L })
        assertTrue(arbiter.permit("a", RetryKind.PROBE))
        assertTrue(arbiter.permit("b", RetryKind.OTA))
        arbiter.reset()
        assertEquals(0, arbiter.used("a", RetryKind.PROBE))
        assertEquals(0, arbiter.used("b", RetryKind.OTA))
    }
}
