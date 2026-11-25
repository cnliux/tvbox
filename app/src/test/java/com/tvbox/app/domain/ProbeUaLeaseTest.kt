package com.tvbox.app.domain

import com.tvbox.app.data.network.GlobalUserAgent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2-16：探针 UA 租约的 JVM 单测。
 * 锁定「并发探针期间不提前还原」——避免先完成的一条把仍在进行中的探针切回播放 UA。
 */
class ProbeUaLeaseTest {

    @Test
    fun `nested leases keep probe ua until last release`() {
        val original = GlobalUserAgent.value
        GlobalUserAgent.value = "Playback/1.0"
        try {
            ProbeUaLease.acquire(UaScope.ProbeUserAgent)
            ProbeUaLease.acquire(UaScope.ProbeUserAgent)
            assertEquals(UaScope.ProbeUserAgent, GlobalUserAgent.value)

            ProbeUaLease.release()
            assertEquals(UaScope.ProbeUserAgent, GlobalUserAgent.value)

            ProbeUaLease.release()
            assertEquals("Playback/1.0", GlobalUserAgent.value)
        } finally {
            // 断言失败时也把租约清干净，避免污染同 JVM 的其它测试。
            repeat(2) { ProbeUaLease.release() }
            GlobalUserAgent.value = original
        }
    }

    @Test
    fun `release without acquire is a no-op`() {
        val original = GlobalUserAgent.value
        GlobalUserAgent.value = "Playback/1.0"
        try {
            ProbeUaLease.release()
            assertEquals("Playback/1.0", GlobalUserAgent.value)
        } finally {
            GlobalUserAgent.value = original
        }
    }
}
