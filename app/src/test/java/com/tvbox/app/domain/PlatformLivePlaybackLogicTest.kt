package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformLivePlaybackLogicTest {

    @Test
    fun emptyCandidatesReturnsNull() {
        val stream = resolved(emptyList(), 0)
        assertNull(nextRotatedPlatformLiveCandidate(stream))
    }

    @Test
    fun rotatesToNextCandidate() {
        val stream = resolved(listOf(c("a"), c("b"), c("c")), 0)
        assertEquals(1, nextRotatedPlatformLiveCandidate(stream))
    }

    @Test
    fun lastCandidateWrapsToNullForReResolve() {
        val stream = resolved(listOf(c("a"), c("b")), 1)
        assertNull(nextRotatedPlatformLiveCandidate(stream))
    }

    @Test
    fun defaultRotationStillWrapsToNullForThreeCandidates() {
        val stream = resolved(listOf(c("a"), c("b"), c("c")), 2)
        assertNull(nextRotatedPlatformLiveCandidate(stream))
    }

    @Test
    fun triedSetAllowsCandidateZeroWhenRotationStartsElsewhere() {
        val stream = resolved(listOf(c("a"), c("b"), c("c")), 1)
        // 候选 1、2 已失败：必须还能尝试从未用过的候选 0，而不是直接触发重新解析。
        assertEquals(0, nextRotatedPlatformLiveCandidate(stream, setOf(1, 2)))
        // 全部试过才返回 null。
        assertNull(nextRotatedPlatformLiveCandidate(stream, setOf(0, 1, 2)))
    }

    @Test
    fun singleCandidateReturnsCurrentIndexForSameLineRetry() {
        // 单候选时不应返回 null（null = 强制重新解析），应返回当前下标让调用方走
        // sameLineRetries 递增 + schedulePlatformLiveReResolve 的退避重解析路径。
        val stream = resolved(listOf(c("a")), 0)
        assertEquals(0, nextRotatedPlatformLiveCandidate(stream))
    }

    @Test
    fun resolveEffectiveEpgUrlPrefersUserConfiguredOverBundled() {
        // 用户显式配置的 EPG 地址优先级最高：即便订阅自带 url-tvg，也优先用用户配置。
        assertEquals("https://user.test/epg.xml", resolveEffectiveEpgUrl(" https://user.test/epg.xml ", " https://bundled.test/epg.xml "))
    }

    @Test
    fun resolveEffectiveEpgUrlFallsBackToBundledWhenUserBlank() {
        // 用户未配置（空串/空白）时回落到订阅自带的 url-tvg，不启用内置默认。
        assertEquals("https://bundled.test/epg.xml", resolveEffectiveEpgUrl("  ", " https://bundled.test/epg.xml "))
        assertEquals("https://bundled.test/epg.xml", resolveEffectiveEpgUrl("", "https://bundled.test/epg.xml"))
    }

    @Test
    fun resolveEffectiveEpgUrlFallsBackToDefaultWhenUserAndBundledBlank() {
        // 用户与订阅都未提供时启用内置默认节目单（开箱即有节目单）。
        assertEquals(
            "https://default.test/epg.xml.gz",
            resolveEffectiveEpgUrl("", "", defaultUrl = "https://default.test/epg.xml.gz"),
        )
        assertEquals(DEFAULT_EPG_URL, resolveEffectiveEpgUrl("", ""))
        // 订阅自带优先于内置默认。
        assertEquals(
            "https://bundled.test/epg.xml",
            resolveEffectiveEpgUrl(" ", " https://bundled.test/epg.xml ", defaultUrl = "https://default.test/epg.xml.gz"),
        )
    }

    @Test
    fun resolveEffectiveEpgUrlReturnsEmptyWhenDefaultAlsoBlank() {
        // 三者皆空才返回空串（节目单显示「暂无节目单」但不阻塞播放）。
        assertEquals("", resolveEffectiveEpgUrl("", "", defaultUrl = ""))
        assertEquals("", resolveEffectiveEpgUrl("   ", "  ", defaultUrl = "  "))
    }

    private fun c(cdn: String) = PlatformLiveStreamCandidate(cdn = cdn, protocol = "flv", url = "https://$cdn.test/live")
    private fun resolved(candidates: List<PlatformLiveStreamCandidate>, active: Int) = ResolvedPlatformLiveStream(
        channelId = "ch",
        title = "t",
        anchor = "a",
        quality = "q",
        headers = emptyMap(),
        candidates = candidates,
        activeCandidateIndex = active,
    )
}
