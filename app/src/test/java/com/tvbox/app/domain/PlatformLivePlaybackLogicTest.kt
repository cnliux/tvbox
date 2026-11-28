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
    fun resolveEffectiveEpgUrlPrefersDefaultOverBundledWhenUserBlank() {
        // 用户未配置时优先内置默认：直播源 x-tvg-url 经常失效，不能压过开箱即用的节目单。
        assertEquals(DEFAULT_EPG_URL, resolveEffectiveEpgUrl("  ", " https://bundled.test/epg.xml "))
        assertEquals(DEFAULT_EPG_URL, resolveEffectiveEpgUrl("", "https://bundled.test/epg.xml"))
        // 内置默认被显式置空时才回落订阅自带。
        assertEquals(
            "https://bundled.test/epg.xml",
            resolveEffectiveEpgUrl("", "https://bundled.test/epg.xml", defaultUrl = ""),
        )
    }

    @Test
    fun resolveEffectiveEpgUrlFallsBackToDefaultWhenUserAndBundledBlank() {
        // 用户与订阅都未提供时启用内置默认节目单（开箱即有节目单）。
        assertEquals(
            "https://default.test/epg.xml.gz",
            resolveEffectiveEpgUrl("", "", defaultUrl = "https://default.test/epg.xml.gz"),
        )
        assertEquals(DEFAULT_EPG_URL, resolveEffectiveEpgUrl("", ""))
        // 内置默认优先于订阅自带（订阅自带仍作为拉取失败时的备用候选）。
        assertEquals(
            "https://default.test/epg.xml.gz",
            resolveEffectiveEpgUrl(" ", " https://bundled.test/epg.xml ", defaultUrl = "https://default.test/epg.xml.gz"),
        )
    }

    @Test
    fun resolveEffectiveEpgUrlReturnsEmptyWhenDefaultAlsoBlank() {
        // 三者皆空才返回空串（节目单显示「暂无节目单」但不阻塞播放）。
        assertEquals("", resolveEffectiveEpgUrl("", "", defaultUrl = ""))
        assertEquals("", resolveEffectiveEpgUrl("   ", "  ", defaultUrl = "  "))
    }

    @Test
    fun resolveEpgSourceLabelPrefersUserConfigured() {
        // 用户显式配置时，无论是否同时存在源自带，来源都标为「用户配置」。
        assertEquals(
            "用户配置",
            resolveEpgSourceLabel(
                userConfiguredUrl = " https://user.test/epg.xml ",
                bundledUrl = "https://bundled.test/epg.xml",
                effectiveUrl = resolveEffectiveEpgUrl(" https://user.test/epg.xml ", "https://bundled.test/epg.xml"),
            ),
        )
    }

    @Test
    fun resolveEpgSourceLabelMarksDefaultWhenUserBlankEvenIfBundledPresent() {
        // 用户未配置且内置默认可用时，来源标为「内置默认」，不是源自带。
        val bundled = "https://bundled.test/epg.xml"
        assertEquals(
            "内置默认",
            resolveEpgSourceLabel(
                userConfiguredUrl = "",
                bundledUrl = bundled,
                effectiveUrl = resolveEffectiveEpgUrl("", bundled),
            ),
        )
        // 内置默认被置空、实际用源自带时才标「源自带」。
        assertEquals(
            "源自带",
            resolveEpgSourceLabel(
                userConfiguredUrl = "",
                bundledUrl = bundled,
                effectiveUrl = resolveEffectiveEpgUrl("", bundled, defaultUrl = ""),
                defaultUrl = "",
            ),
        )
    }

    @Test
    fun resolveEpgSourceLabelMarksDefaultWhenUserAndBundledBlank() {
        // 用户与订阅都未提供时启用内置默认，来源标为「内置默认」（开箱即有节目单）。
        assertEquals(
            "内置默认",
            resolveEpgSourceLabel(
                userConfiguredUrl = "",
                bundledUrl = "",
                effectiveUrl = resolveEffectiveEpgUrl("", ""),
            ),
        )
    }

    @Test
    fun resolveEpgSourceLabelReturnsUnsetWhenEffectiveUrlBlank() {
        // 三层都未提供 → effectiveUrl 为空 → 标「未设置」。
        assertEquals(
            "未设置",
            resolveEpgSourceLabel(userConfiguredUrl = "", bundledUrl = "", effectiveUrl = ""),
        )
    }

    @Test
    fun epgFetchCandidatesKeepsPriorityOrderAndDeduplicates() {
        assertEquals(
            listOf(
                "https://user.test/epg.xml",
                "https://default.test/epg.xml.gz",
                "https://bundled.test/epg.xml",
            ),
            epgFetchCandidates(
                " https://user.test/epg.xml ",
                "https://bundled.test/epg.xml",
                "https://default.test/epg.xml.gz",
            ),
        )
        // 同一地址出现在多层时只保留首次，避免重复拉取同一个失效 URL。
        assertEquals(
            listOf("https://user.test/epg.xml", "https://default.test/epg.xml.gz"),
            epgFetchCandidates(
                "https://user.test/epg.xml",
                " https://user.test/epg.xml ",
                "https://default.test/epg.xml.gz",
            ),
        )
        assertEquals(emptyList<String>(), epgFetchCandidates("  ", "", "  "))
    }

    @Test
    fun epgSourceLabelUsesActuallyFetchedUrl() {
        // 回退后展示实际使用的地址与来源：内置默认生效时标「内置默认」，不能沿用失效的用户配置标签。
        val default = "https://default.test/epg.xml.gz"
        assertEquals(
            "内置默认",
            resolveEpgSourceLabel(
                userConfiguredUrl = "",
                bundledUrl = "http://epg.51zmt.top:8000/cc.xml.gz",
                effectiveUrl = "http://epg.51zmt.top:8000/cc.xml.gz",
                defaultUrl = default,
                usedUrl = default,
            ),
        )
        assertEquals(
            "内置默认",
            resolveEpgSourceLabel(
                userConfiguredUrl = "https://user.test/epg.xml",
                bundledUrl = "",
                effectiveUrl = "https://user.test/epg.xml",
                defaultUrl = default,
                usedUrl = default,
            ),
        )
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
