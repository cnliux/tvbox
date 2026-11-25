package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyLiveProxyTest {

    @Test
    fun `parseTemplates splits lines and drops blanks, comments and duplicates`() {
        val raw = """
            https://a.example/{site}/{room}
            # 注释行
            https://b.example/{site}/{room}
            https://a.example/{site}/{room}
        """.trimIndent()
        val templates = ThirdPartyLiveProxy.parseTemplates(raw)
        assertEquals(
            listOf(
                "https://a.example/{site}/{room}",
                "https://b.example/{site}/{room}",
            ),
            templates,
        )
    }

    @Test
    fun `expand replaces room and site placeholders`() {
        assertEquals(
            "https://mursor.ottiptv.cc/douyu/36252",
            ThirdPartyLiveProxy.expand("https://mursor.ottiptv.cc/{site}/{room}", "douyu", "36252"),
        )
    }

    @Test
    fun `expand appends site and room when no placeholder`() {
        assertEquals(
            "https://host.example/huya/11342412",
            ThirdPartyLiveProxy.expand("https://host.example", "huya", "11342412"),
        )
    }

    @Test
    fun `expand with only site placeholder appends room`() {
        assertEquals(
            "https://host.example/douyu/99",
            ThirdPartyLiveProxy.expand("https://host.example/{site}", "douyu", "99"),
        )
    }

    @Test
    fun `expand returns empty for blank room or template`() {
        assertEquals("", ThirdPartyLiveProxy.expand("https://host/{room}", "douyu", "  "))
        assertEquals("", ThirdPartyLiveProxy.expand("   ", "douyu", "99"))
    }

    @Test
    fun `candidateUrls keeps order and only for supported sites`() {
        val templates = listOf("https://a/{site}/{room}", "https://b/{site}/{room}")
        val urls = ThirdPartyLiveProxy.candidateUrls(templates, "douyu", "36252")
        assertEquals(listOf("https://a/douyu/36252", "https://b/douyu/36252"), urls)

        // 站点不受支持 → 空列表（不生成第三方候选）。
        assertTrue(ThirdPartyLiveProxy.candidateUrls(templates, "bilibili", "1").isEmpty())
    }

    @Test
    fun `default templates cover huya and douyu`() {
        assertTrue(ThirdPartyLiveProxy.isSupported("huya"))
        assertTrue(ThirdPartyLiveProxy.isSupported("douyu"))
        assertTrue(!ThirdPartyLiveProxy.isSupported("bilibili"))
        val douyu = ThirdPartyLiveProxy.candidateUrls(ThirdPartyLiveProxy.defaultTemplates, "douyu", "36252")
        assertEquals(listOf("https://mursor.ottiptv.cc/douyu/36252"), douyu)
        val huya = ThirdPartyLiveProxy.candidateUrls(ThirdPartyLiveProxy.defaultTemplates, "huya", "11342412")
        assertEquals(listOf("https://mursor.ottiptv.cc/huya/11342412"), huya)
    }
}
