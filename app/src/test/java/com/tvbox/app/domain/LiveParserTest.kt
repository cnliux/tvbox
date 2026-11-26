package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直播源解析命脉的补充回归测试。
 *
 * 与 LiveMultiLineParserTest 互补：这里锁定 TXT `#genre#` 分组语义、
 * `$` 两侧地址、M3U 跨分组重复 URL 去重，以及 EPG 地址提取的各个头部属性变体。
 */
class LiveParserTest {

    @Test
    fun txtGenreGroupingAppliesToFollowingChannels() {
        val raw = """
            央视,#genre#
            CCTV-1,http://a.example/1.flv
            CCTV-2,http://a.example/2.flv
            卫视,#genre#
            湖南卫视,http://a.example/hn.flv
        """.trimIndent()
        val channels = parseLiveChannels(raw)
        assertEquals(3, channels.size)
        assertEquals("央视", channels[0].groupName)
        assertEquals("央视", channels[1].groupName)
        assertEquals("卫视", channels[2].groupName)
        assertEquals("CCTV-1", channels[0].name)
    }

    @Test
    fun txtDollarMetadataOnEitherSideIsRecognized() {
        val raw = """
            测试,http://a.example/t1.flv${'$'}LR•IPV4•29『线路9』
            测试2,线路A${'$'}http://a.example/t2.flv
        """.trimIndent()
        val channels = parseLiveChannels(raw)
        assertEquals(2, channels.size)
        assertEquals("http://a.example/t1.flv", channels[0].lines.single().url)
        assertEquals("线路9", channels[0].lines.single().name)
        assertEquals("http://a.example/t2.flv", channels[1].lines.single().url)
        assertEquals("线路A", channels[1].lines.single().name)
    }

    @Test
    fun txtUrlWithEmbeddedCommaIsNotSplit() {
        val raw = "测试,http://a.example/01.m3u8?fmt=ts2hls,244,01.m3u8&accountinfo=xxx"
        val channels = parseLiveChannels(raw)
        assertEquals(1, channels.size)
        assertEquals(1, channels[0].lines.size)
        assertEquals(raw.substringAfter(','), channels[0].lines.single().url)
    }

    @Test
    fun m3uDuplicateUrlAcrossGroupsIsDeduped() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 group-title="全部",CCTV-1
            http://a.example/1.m3u8
            #EXTINF:-1 group-title="央视",CCTV-1
            http://a.example/1.m3u8
            #EXTINF:-1 group-title="央视",CCTV-2
            http://a.example/2.m3u8
        """.trimIndent()
        val channels = parseLiveChannels(raw)
        assertEquals(2, channels.size)
        assertEquals("CCTV-1", channels[0].name)
        assertEquals("CCTV-2", channels[1].name)
    }

    @Test
    fun extractM3uEpgUrlPicksFirstHttpFromKnownAttributes() {
        val raw = """
            #EXTM3U url-tvg="ftp://x/epg.xml, https://a.example/epg.xml"
            #EXTINF:-1,CCTV-1
            http://a.example/1.m3u8
        """.trimIndent()
        assertEquals("https://a.example/epg.xml", extractM3uEpgUrl(raw))
    }

    @Test
    fun extractM3uEpgUrlSupportsXouseTvgAttributeWithSpaces() {
        val raw = "#EXTM3U x-tvg-url = \"https://b.example/epg.xml\""
        assertEquals("https://b.example/epg.xml", extractM3uEpgUrl(raw))
    }

    @Test
    fun extractM3uEpgUrlSupportsUrlEpgAttribute() {
        val raw = "#EXTM3U url-epg=\"https://c.example/epg.xml\""
        assertEquals("https://c.example/epg.xml", extractM3uEpgUrl(raw))
    }

    @Test
    fun extractM3uEpgUrlReturnsNullWhenAbsent() {
        assertNull(extractM3uEpgUrl("#EXTM3U\n#EXTINF:-1,CCTV-1\nhttp://a.example/1.m3u8"))
    }

    @Test
    fun htmlDirectoryPageYieldsNoChannels() {
        val raw = """<html><body><a href="a.txt">a.txt</a>CCTV-1,http://x</body></html>"""
        assertTrue(parseLiveChannels(raw).isEmpty())
    }

    @Test
    fun m3uMissingTvgIdFoldsByNameButDistinctIdsStaySeparate() {
        val noId = """
            #EXTM3U
            #EXTINF:-1 tvg-name="央视综合",央视综合
            http://a.example/1.m3u8
            #EXTINF:-1 tvg-name="央视综合",央视综合
            http://b.example/2.m3u8
        """.trimIndent()
        val folded = parseLiveChannels(noId)
        assertEquals(1, folded.size)
        assertEquals(2, folded.single().lines.size)

        val withIds = """
            #EXTM3U
            #EXTINF:-1 tvg-id="CCTV1" tvg-name="央视综合",央视综合
            http://a.example/1.m3u8
            #EXTINF:-1 tvg-id="CCTV2" tvg-name="央视综合",央视综合
            http://b.example/2.m3u8
        """.trimIndent()
        val separate = parseLiveChannels(withIds)
        assertEquals(2, separate.size)
        assertEquals("CCTV1", separate[0].tvgId)
        assertEquals("CCTV2", separate[1].tvgId)
    }

    @Test
    fun m3uGroupNameWithQuoteAndPipeIsNotTruncated() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视|高清\"测试",CCTV-1
            http://a.example/1.m3u8
        """.trimIndent()
        val channels = parseLiveChannels(raw)
        assertEquals(1, channels.size)
        assertEquals("央视|高清\"测试", channels.single().groupName)
    }

    @Test
    fun m3uDuplicateChannelNameFoldsIntoOneChannelWithFiveLines() {
        val raw = buildString {
            append("#EXTM3U\n")
            repeat(5) { i ->
                append("#EXTINF:-1 tvg-id=\"CCTV1\" tvg-name=\"CCTV-1\" group-title=\"央视\",CCTV-1\n")
                append("https://cdn$i.example.com/live.m3u8\n")
            }
        }
        val channels = parseLiveChannels(raw)
        assertEquals(1, channels.size)
        assertEquals("CCTV-1", channels.single().name)
        assertEquals(5, channels.single().lines.size)
        assertEquals(
            (0..4).map { "https://cdn$it.example.com/live.m3u8" },
            channels.single().lines.map { it.url },
        )
    }

    @Test
    fun m3uExtVlcoptHeadersAndRtspLineSurviveLexerSwitch() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="测试",测试
            #EXTVLCOPT:http-referrer=https://ref.example/
            http://a.example/1.m3u8
            #EXTINF:-1 tvg-name="监控",监控
            rtsp://cam.example/live
        """.trimIndent()
        val channels = parseLiveChannels(raw)
        assertEquals(2, channels.size)
        assertEquals("https://ref.example/", channels[0].lines.single().headers["Referer"])
        assertEquals("rtsp://cam.example/live", channels[1].lines.single().url)
    }
}
