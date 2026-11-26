package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S3-19：EPG 缓存 stale-while-revalidate 纯函数回归。 */
class EpgCachePolicyTest {

    private val now = 10_000L * 3_600_000L // 10h 基准时刻

    @Test
    fun `no cache renders as loading`() {
        assertEquals(EpgRenderState.LOADING, EpgCachePolicy.renderState(null, now))
        // 空节目单视同无缓存。
        assertEquals(EpgRenderState.LOADING, EpgCachePolicy.renderState(EpgData(emptyMap(), now), now))
    }

    @Test
    fun `cache within 7 days renders as fresh without tilde`() {
        val cached = EpgData(mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L))), now - 6L * 24L * 3_600_000L)
        val state = EpgCachePolicy.renderState(cached, now)
        assertEquals(EpgRenderState.FRESH, state)
        assertFalse(state.showWithTilde)
        assertFalse(EpgCachePolicy.shouldFetch(cached, now))
    }

    @Test
    fun `cache at exactly 7 days boundary is stale`() {
        val cached = EpgData(mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L))), now - EpgCachePolicy.FRESH_TTL_MS)
        val state = EpgCachePolicy.renderState(cached, now)
        assertEquals(EpgRenderState.STALE, state)
        assertTrue(state.showWithTilde)
        assertTrue(EpgCachePolicy.shouldFetch(cached, now))
    }

    @Test
    fun `cache beyond 7 days is stale with tilde and revalidate`() {
        val cached = EpgData(mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L))), now - 30L * 24L * 3_600_000L)
        val state = EpgCachePolicy.renderState(cached, now)
        assertEquals(EpgRenderState.STALE, state)
        assertTrue(state.showWithTilde)
        assertTrue(EpgCachePolicy.shouldFetch(cached, now))
    }

    @Test
    fun `fresh state requires no fetch`() {
        val cached = EpgData(mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L))), now - 1_000L)
        assertFalse(EpgCachePolicy.shouldFetch(cached, now))
    }
}
