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
    fun singleCandidateReturnsCurrentIndexForSameLineRetry() {
        // 单候选时不应返回 null（null = 强制重新解析），应返回当前下标让调用方走
        // sameLineRetries 递增 + schedulePlatformLiveReResolve 的退避重解析路径。
        val stream = resolved(listOf(c("a")), 0)
        assertEquals(0, nextRotatedPlatformLiveCandidate(stream))
    }

    @Test
    fun resolveEffectiveEpgUrlPrefersUserConfig() {
        assertEquals("https://user.test/epg.xml", resolveEffectiveEpgUrl(" https://user.test/epg.xml ", "https://bundled.test/epg.xml"))
        assertEquals("https://bundled.test/epg.xml", resolveEffectiveEpgUrl("  ", "https://bundled.test/epg.xml"))
        assertEquals("", resolveEffectiveEpgUrl("", ""))
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
