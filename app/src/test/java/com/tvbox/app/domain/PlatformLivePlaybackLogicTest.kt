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
    fun resolveEffectiveEpgUrlPrefersBundledOverUserBackup() {
        // 订阅自带 EPG 优先：即便用户已配置备用地址，也优先使用订阅的 url-tvg。
        assertEquals("https://bundled.test/epg.xml", resolveEffectiveEpgUrl(" https://user.test/epg.xml ", " https://bundled.test/epg.xml "))
    }

    @Test
    fun resolveEffectiveEpgUrlFallsBackToUserBackupWhenBundledBlank() {
        // 订阅未自带（空串/空白）时回落到用户配置的备用地址。
        assertEquals("https://user.test/epg.xml", resolveEffectiveEpgUrl(" https://user.test/epg.xml ", "  "))
        assertEquals("https://user.test/epg.xml", resolveEffectiveEpgUrl("https://user.test/epg.xml", ""))
    }

    @Test
    fun resolveEffectiveEpgUrlFallsBackToBuiltinWhenBothBlank() {
        // 两者都未提供时回退内置兜底 EPG（APK 兜底，节目单不再永远空白）。
        assertEquals(BUILTIN_FALLBACK_EPG_URL, resolveEffectiveEpgUrl("", ""))
        assertEquals(BUILTIN_FALLBACK_EPG_URL, resolveEffectiveEpgUrl("   ", "  "))
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
