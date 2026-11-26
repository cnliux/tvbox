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
    fun `cache within fresh window renders as fresh without tilde`() {
        // FRESH 窗口为 6 小时：5 小时前拉取仍算新鲜，直接渲染不加角标。
        val cached = EpgData(mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L))), now - 5L * 3_600_000L)
        val state = EpgCachePolicy.renderState(cached, now)
        assertEquals(EpgRenderState.FRESH, state)
        assertFalse(state.showWithTilde)
        assertFalse(EpgCachePolicy.shouldFetch(cached, now))
    }

    @Test
    fun `cache at exactly fresh boundary is stale`() {
        val cached = EpgData(mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L))), now - EpgCachePolicy.FRESH_TTL_MS)
        val state = EpgCachePolicy.renderState(cached, now)
        assertEquals(EpgRenderState.STALE, state)
        assertTrue(state.showWithTilde)
        assertTrue(EpgCachePolicy.shouldFetch(cached, now))
    }

    @Test
    fun `cache beyond fresh window is stale with tilde and revalidate`() {
        // 超过 6 小时窗口（隔天）：节目单应刷新，旧数据加角标并后台重拉。
        val cached = EpgData(mapOf("cctv1" to listOf(EpgProgram("节目", 0L, 60_000L))), now - 30L * 3_600_000L)
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
