package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedPlatformLiveStreamThirdPartyTest {

    private fun resolved(vararg urls: String): ResolvedPlatformLiveStream =
        ResolvedPlatformLiveStream(
            channelId = "douyu:36252",
            title = "t",
            anchor = "a",
            quality = "原画",
            headers = emptyMap(),
            candidates = urls.mapIndexed { i, u ->
                PlatformLiveStreamCandidate(cdn = "官方$i", protocol = "flv", url = u)
            },
        )

    @Test
    fun `third-party candidates are prepended and official kept as fallback`() {
        val base = resolved("https://cdn.douyu/1.flv", "https://cdn.douyu/2.flv")
        val merged = base.withThirdPartyProxyPrepended(
            site = "douyu",
            roomId = "36252",
            enabled = true,
            templatesRaw = "https://a/{site}/{room}\nhttps://b/{site}/{room}",
        )
        assertEquals(
            listOf("https://a/douyu/36252", "https://b/douyu/36252", "https://cdn.douyu/1.flv", "https://cdn.douyu/2.flv"),
            merged.candidates.map { it.url },
        )
        assertEquals("第三方", merged.candidates.first().cdn)
    }

    @Test
    fun `disabled or unsupported site returns original candidates`() {
        val base = resolved("https://cdn.douyu/1.flv")
        assertEquals(
            base.candidates,
            base.withThirdPartyProxyPrepended("douyu", "36252", enabled = false, templatesRaw = "https://a/{site}/{room}").candidates,
        )
        // 真正不受第三方支持的平台（不在 supportedSites）不生成候选。
        assertEquals(
            base.candidates,
            base.withThirdPartyProxyPrepended("youtube", "1", enabled = true, templatesRaw = "https://a/{site}/{room}").candidates,
        )
    }

    @Test
    fun `empty templates fall back to built-in default`() {
        val base = resolved("https://cdn.douyu/1.flv")
        val merged = base.withThirdPartyProxyPrepended("douyu", "36252", enabled = true, templatesRaw = "")
        // 内置默认双模板：cfss.cc 主 + mursor 备用，官方候选退为兜底。
        assertEquals(
            listOf(
                "https://cfss.cc/cdn/douyu/36252.flv",
                "https://mursor.ottiptv.cc/douyu/36252",
                "https://cdn.douyu/1.flv",
            ),
            merged.candidates.map { it.url },
        )
        assertEquals("第三方", merged.candidates.first().cdn)
    }

    @Test
    fun `duplicate third-party url not inserted twice`() {
        // base 已含 mursor 候选，默认双模板展开后 mursor 去重，只补入 cfss.cc。
        val base = resolved("https://mursor.ottiptv.cc/douyu/36252")
        val merged = base.withThirdPartyProxyPrepended("douyu", "36252", enabled = true, templatesRaw = "")
        assertEquals(
            listOf("https://cfss.cc/cdn/douyu/36252.flv", "https://mursor.ottiptv.cc/douyu/36252"),
            merged.candidates.map { it.url },
        )
    }
}
