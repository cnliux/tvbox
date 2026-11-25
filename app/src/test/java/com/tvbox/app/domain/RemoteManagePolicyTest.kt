package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1-01：默认只听回环 + 失败退避的纯领域回归。 */
class RemoteManagePolicyTest {

    @Test
    fun bindsLoopbackUnlessExplicitlyOptedIn() {
        assertEquals("127.0.0.1", RemoteManagePolicy.bindHost(false))
        assertEquals("0.0.0.0", RemoteManagePolicy.bindHost(true))
        assertEquals("127.0.0.1", RemoteManagePolicy.displayHost(false, "192.168.1.23"))
        assertEquals("192.168.1.23", RemoteManagePolicy.displayHost(true, "192.168.1.23"))
    }

    @Test
    fun locksAfterFiveFailuresAndExpiresAfterThirtySeconds() {
        var now = 1_000L
        val throttle = RemoteLoginThrottle(clock = { now })
        repeat(4) { assertFalse(throttle.recordFailure("192.168.1.9", now)) }
        assertFalse("4 次失败不应锁", throttle.isLocked("192.168.1.9", now))
        assertTrue("第 5 次失败应锁定", throttle.recordFailure("192.168.1.9", now))
        assertTrue(throttle.isLocked("192.168.1.9", now))
        assertTrue(throttle.isLocked("192.168.1.9", now + 29_999L))
        assertFalse("30s 后应自动解锁", throttle.isLocked("192.168.1.9", now + 30_000L))
    }

    @Test
    fun successClearsFailuresAndIpIsolationHolds() {
        val throttle = RemoteLoginThrottle(clock = { 100L })
        throttle.recordFailure("10.0.0.1", 100L)
        throttle.recordFailure("10.0.0.1", 100L)
        assertTrue("另一 IP 不应受影响", !throttle.isLocked("10.0.0.2", 100L))
        throttle.recordSuccess("10.0.0.1")
        repeat(4) { assertFalse(throttle.recordFailure("10.0.0.1", 100L)) }
        assertFalse("成功应清零计数", throttle.isLocked("10.0.0.1", 100L))
    }
}
