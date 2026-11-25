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
    fun `siteAlias maps full name to short code`() {
        assertEquals("hy", ThirdPartyLiveProxy.siteAlias("huya"))
        assertEquals("hy", ThirdPartyLiveProxy.siteAlias(" HUYA "))
        assertEquals("dy", ThirdPartyLiveProxy.siteAlias("douyu"))
        assertEquals("bl", ThirdPartyLiveProxy.siteAlias("bilibili"))
        // 未登记短码的平台返回空串。
        assertEquals("", ThirdPartyLiveProxy.siteAlias("youtube"))
    }

    @Test
    fun `expand supports siteAlias placeholder for short-code urls`() {
        // 形如 https://host/hy/房间号.flv（虎牙）、https://host/dy/房间号.flv（斗鱼）——短码 + 后缀风格。
        // 具体第三方域名不写死，模板完全由用户参数化配置。
        val template = "https://host/{siteAlias}/{room}.flv"
        assertEquals("https://host/hy/11342412.flv", ThirdPartyLiveProxy.expand(template, "huya", "11342412"))
        assertEquals("https://host/dy/36252.flv", ThirdPartyLiveProxy.expand(template, "douyu", "36252"))
        // {siteAlias} 仍受 supportedSites 约束：不支持的站点不生成候选。
        assertTrue(ThirdPartyLiveProxy.candidateUrls(listOf(template), "youtube", "1").isEmpty())
    }

    @Test
    fun `expand keeps full-name site and alias independent`() {
        // {site}（全名）与 {siteAlias}（短码）在同一条模板里各自正确替换。
        assertEquals(
            "https://host/huya/hy/99.flv",
            ThirdPartyLiveProxy.expand("https://host/{site}/{siteAlias}/{room}.flv", "huya", "99"),
        )
        assertEquals(
            "https://host/douyu/dy/88.flv",
            ThirdPartyLiveProxy.expand("https://host/{site}/{siteAlias}/{room}.flv", "douyu", "88"),
        )
    }

    @Test
    fun `candidateUrls keeps order and only for supported sites`() {
        val templates = listOf("https://a/{site}/{room}", "https://b/{site}/{room}")
        val urls = ThirdPartyLiveProxy.candidateUrls(templates, "douyu", "36252")
        assertEquals(listOf("https://a/douyu/36252", "https://b/douyu/36252"), urls)
        // 真正不受支持的站点（不在 supportedSites）不生成候选。
        assertTrue(ThirdPartyLiveProxy.candidateUrls(templates, "youtube", "1").isEmpty())
    }

    @Test
    fun `supportedSites covers all ten go adapters`() {
        val expected = setOf(
            "douyu", "huya", "bilibili", "cc", "douyin",
            "kuaishou", "inke", "showroom", "weibo", "missevan",
        )
        assertEquals(expected, ThirdPartyLiveProxy.supportedSites)
        expected.forEach { assertTrue("应支持 $it", ThirdPartyLiveProxy.isSupported(it)) }
        assertTrue(!ThirdPartyLiveProxy.isSupported("youtube"))
    }

    @Test
    fun `default templates cover huya and douyu`() {
        // 内置默认双模板：cfss.cc 主（走续流代理）+ mursor 备用（直连），多备用顺序保持。
        val douyu = ThirdPartyLiveProxy.candidateUrls(ThirdPartyLiveProxy.defaultTemplates, "douyu", "36252")
        assertEquals(
            listOf("https://cfss.cc/cdn/douyu/36252.flv", "https://mursor.ottiptv.cc/douyu/36252"),
            douyu,
        )
        val huya = ThirdPartyLiveProxy.candidateUrls(ThirdPartyLiveProxy.defaultTemplates, "huya", "11342412")
        assertEquals(
            listOf("https://cfss.cc/cdn/huya/11342412.flv", "https://mursor.ottiptv.cc/huya/11342412"),
            huya,
        )
    }

    // ── A 方案：按站点分组（`# 站点名` 头 + 以房间号为主键）─────────────────────

    @Test
    fun `parseGroupedTemplates assigns templates per site and default group`() {
        val raw = """
            https://fallback.example/{site}/{room}
            # douyu
            https://douyu-a.example/{room}.flv
            # huya
            https://huya-a.example/{siteAlias}/{room}.flv
            https://huya-b.example/{site}/{room}
        """.trimIndent()
        val grouped = ThirdPartyLiveProxy.parseGroupedTemplates(raw)
        assertEquals(
            listOf("https://fallback.example/{site}/{room}"),
            grouped[ThirdPartyLiveProxy.DEFAULT_GROUP],
        )
        assertEquals(
            listOf("https://douyu-a.example/{room}.flv"),
            grouped["douyu"],
        )
        assertEquals(
            listOf("https://huya-a.example/{siteAlias}/{room}.flv", "https://huya-b.example/{site}/{room}"),
            grouped["huya"],
        )
    }

    @Test
    fun `templatesForSite prefers site group and appends default group as fallback`() {
        val raw = """
            https://fallback.example/{site}/{room}
            # douyu
            https://douyu-a.example/{room}.flv
        """.trimIndent()
        // douyu：专属分组在前，默认组兜底在后。
        assertEquals(
            listOf("https://douyu-a.example/{room}.flv", "https://fallback.example/{site}/{room}"),
            ThirdPartyLiveProxy.templatesForSite(raw, "douyu"),
        )
        // bilibili：无专属分组，只用默认组兜底。
        assertEquals(
            listOf("https://fallback.example/{site}/{room}"),
            ThirdPartyLiveProxy.templatesForSite(raw, "bilibili"),
        )
        // 不受支持站点：空。
        assertTrue(ThirdPartyLiveProxy.templatesForSite(raw, "youtube").isEmpty())
    }

    @Test
    fun `group header accepts chinese name and short alias`() {
        val raw = """
            # 斗鱼
            https://douyu-cn.example/{room}.flv
            # hy
            https://huya-alias.example/{room}.flv
        """.trimIndent()
        val grouped = ThirdPartyLiveProxy.parseGroupedTemplates(raw)
        assertEquals(listOf("https://douyu-cn.example/{room}.flv"), grouped["douyu"])
        assertEquals(listOf("https://huya-alias.example/{room}.flv"), grouped["huya"])
    }

    @Test
    fun `candidateUrlsFromGrouped expands per-site template with roomId as key`() {
        val raw = """
            # douyu
            https://cfss.example/cdn/dy/{room}.flv
            # huya
            https://cfss.example/cdn/hy/{room}.flv
        """.trimIndent()
        // 同一份分组配置，douyu / huya 各自用自家模板、以房间号展开。
        assertEquals(
            listOf("https://cfss.example/cdn/dy/36252.flv"),
            ThirdPartyLiveProxy.candidateUrlsFromGrouped(raw, "douyu", "36252"),
        )
        assertEquals(
            listOf("https://cfss.example/cdn/hy/11342412.flv"),
            ThirdPartyLiveProxy.candidateUrlsFromGrouped(raw, "huya", "11342412"),
        )
        // 站点未配置且不在默认组：空（不凭空生成其它平台的候选）。
        assertTrue(
            ThirdPartyLiveProxy.candidateUrlsFromGrouped(raw, "bilibili", "1").isEmpty(),
        )
    }

    @Test
    fun `legacy ungrouped config still works via default group`() {
        // 旧的、没有任何 `# 站点` 分组头的配置：全部归默认组，对所有平台兜底（向后兼容）。
        val raw = "https://legacy.example/{site}/{room}\nhttps://legacy2.example/{siteAlias}/{room}.flv"
        assertEquals(
            listOf("https://legacy.example/douyu/36252", "https://legacy2.example/dy/36252.flv"),
            ThirdPartyLiveProxy.candidateUrlsFromGrouped(raw, "douyu", "36252"),
        )
    }
}
