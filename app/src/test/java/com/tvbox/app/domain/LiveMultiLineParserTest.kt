package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多线路聚合与真实源格式回归。
 *
 * 覆盖三类线上真实源导致"大面积无法播放"的解析缺陷：
 * `$` 后线路元数据被当成地址、地址自带逗号被截断、HTML 目录页被当成频道。
 */
class LiveMultiLineParserTest {

    @Test
    fun keepsUrlBeforeDollarAndTreatsTextAfterDollarAsLineName() {
        // 真实源格式：频道名,地址$LR•IPV4•29『线路N』。`$` 之前才是地址。
        val channels = parseLiveChannels(
            """
            凤凰卫视,https://live.test/fh/index.m3u8${'$'}LR•IPV4•29『线路1』
            凤凰卫视,https://backup.test/fh/index.m3u8${'$'}LR•IPV4•29『线路2』
            """.trimIndent(),
        )

        val channel = channels.single()
        assertEquals("凤凰卫视", channel.name)
        assertEquals(2, channel.lines.size)
        assertEquals("https://live.test/fh/index.m3u8", channel.lines[0].url)
        assertEquals("线路1", channel.lines[0].name)
        assertEquals("https://backup.test/fh/index.m3u8", channel.lines[1].url)
        assertEquals("线路2", channel.lines[1].name)
    }

    @Test
    fun keepsCommasInsideUrlIntact() {
        // IPTV 地址自带逗号（ts2hls 三段式 + accountinfo），不能被逗号切分成多条线路。
        val tricky = "https://live.test/smil:news.smil/01.m3u8?fmt=ts2hls,244,01.m3u8&accountinfo=abc%3Ddef"
        val channels = parseLiveChannels("新闻频道,$tricky")

        val channel = channels.single()
        assertEquals(1, channel.lines.size)
        assertEquals(tricky, channel.lines.single().url)
    }

    @Test
    fun splitsMultipleUrlsOnOneLine() {
        // 也支持真正的"一行多线路"：逗号后是另一个地址时才切开。
        val channels = parseLiveChannels(
            """
            湖南影视,http://a.test/1.m3u8,电信${'$'}http://b.test/2.m3u8
            """.trimIndent(),
        )

        val channel = channels.single()
        assertEquals(2, channel.lines.size)
        assertEquals("http://a.test/1.m3u8", channel.lines[0].url)
        assertEquals("http://b.test/2.m3u8", channel.lines[1].url)
    }

    @Test
    fun supportsFullWidthCommaAndPipeSeparatedAliases() {
        // 部分源用全角逗号；名称里带 | 的别名只影响频道名，不影响地址解析。
        val channels = parseLiveChannels("广东少儿，https://live.test/gdse/index.m3u8")

        val channel = channels.single()
        assertEquals("广东少儿", channel.name)
        assertEquals("https://live.test/gdse/index.m3u8", channel.lines.single().url)
    }

    @Test
    fun ignoresHtmlDirectoryPages() {
        // jsDelivr / gh-proxy 目录页不是直播源，解析结果必须为空而不是"假频道"。
        val html = """
            <!DOCTYPE html>
            <html><head><title>Index of /</title></head>
            <body><a href="huyayqk.m3u">huyayqk.m3u</a></body></html>
        """.trimIndent()

        assertTrue(parseLiveChannels(html).isEmpty())
    }

    @Test
    fun aggregatesSameNameChannelsWithinGroupIntoLines() {
        // M3U 源里同名 tvg-name 多条：聚合成一个频道多条线路，而不是产生 N 个同名频道。
        val channels = parseLiveChannels(
            """
            #EXTM3U
            #EXTINF:-1 group-title="央视" tvg-name="CCTV1",CCTV1 高清
            https://a.test/cctv1.m3u8
            #EXTINF:-1 group-title="央视" tvg-name="CCTV1",CCTV1 标清
            https://b.test/cctv1.m3u8
            """.trimIndent(),
        )

        val channel = channels.single()
        assertEquals("CCTV1", channel.name)
        assertEquals("央视", channel.groupName)
        assertEquals(2, channel.lines.size)
        assertEquals("CCTV1 高清", channel.lines[0].name)
        assertEquals("https://b.test/cctv1.m3u8", channel.lines[1].url)
    }

    @Test
    fun parsesExtVlcOptHeadersPerLine() {
        // #EXTVLCOPT 的 UA/Referer 只作用于紧随其后的那条线路。
        val channels = parseLiveChannels(
            """
            #EXTM3U
            #EXTINF:-1 group-title="综合" tvg-name="测试频道",测试频道
            #EXTVLCOPT:http-user-agent=TVBoxTest/1.0
            #EXTVLCOPT:http-referrer=https://refer.test/
            https://live.test/protected.m3u8
            #EXTINF:-1 group-title="综合" tvg-name="测试频道",测试频道备份
            https://live.test/backup.m3u8
            """.trimIndent(),
        )

        val channel = channels.single()
        assertEquals(2, channel.lines.size)
        assertEquals("TVBoxTest/1.0", channel.lines[0].headers["User-Agent"])
        assertEquals("https://refer.test/", channel.lines[0].headers["Referer"])
        assertTrue(channel.lines[1].headers.isEmpty())
    }

    @Test
    fun keepsSameChannelNameAcrossDifferentGroups() {
        // "CCTV4" 在多个分类里都出现，聚合键必须是（分类, 频道名），否则跨分类频道会被合并丢失。
        val channels = parseLiveChannels(
            """
            央视,#genre#
            CCTV4,https://a.test/cctv4.m3u8
            高清,#genre#
            CCTV4,https://b.test/cctv4.m3u8
            """.trimIndent(),
        )

        assertEquals(2, channels.size)
        assertEquals(listOf("央视", "高清"), channels.map { it.groupName })
        assertTrue(channels.all { it.name == "CCTV4" })
    }

    @Test
    fun acceptsRtmpAndFlvCandidatesAndMarksProtocol() {
        val channels = parseLiveChannels(
            """
            综合,#genre#
            电影1台,rtmp://live.test/live/movie1
            电影2台,https://live.test/live/movie2.flv
            """.trimIndent(),
        )

        assertEquals(2, channels.size)
        assertEquals("rtmp", channels[0].lines.single().protocolHint)
        assertEquals("flv", channels[1].lines.single().protocolHint)
    }

    @Test
    fun extGrpAfterExtInfUpdatesGroup() {
        val channels = parseLiveChannels(
            """
            #EXTM3U
            #EXTINF:-1 group-title="原始分组" tvg-name="测试台",测试台
            #EXTGRP:覆盖分组
            https://live.test/a.m3u8
            """.trimIndent(),
        )

        val channel = channels.single()
        assertEquals("测试台", channel.name)
        assertEquals("覆盖分组", channel.groupName)
    }

    @Test
    fun extGrpBlankFallsBackToCurrentGroup() {
        val channels = parseLiveChannels(
            """
            #EXTM3U
            #EXTINF:-1 group-title="保留分组" tvg-name="测试台",测试台
            #EXTGRP:
            https://live.test/a.m3u8
            """.trimIndent(),
        )

        val channel = channels.single()
        assertEquals("保留分组", channel.groupName)
    }

    @Test
    fun splitsUrlBeforeDollarLinesOnOneTxtLine() {
        // 真实源最常见格式：地址在前、`$` 后是线路元数据；同一行第 2..N 条线路不能被拼进前一条地址。
        val channels = parseLiveChannels(
            "央视一套,http://a.test/1.m3u8${'$'}LR•IPV4•29『线路1』," +
                "http://b.test/2.m3u8${'$'}LR•IPV4•29『线路2』",
        )

        val channel = channels.single()
        assertEquals(2, channel.lines.size)
        assertEquals("http://a.test/1.m3u8", channel.lines[0].url)
        assertEquals("线路1", channel.lines[0].name)
        assertEquals("http://b.test/2.m3u8", channel.lines[1].url)
        assertEquals("线路2", channel.lines[1].name)
    }

    @Test
    fun extGrpWithoutPendingChannelDoesNotBreakParsing() {
        val channels = parseLiveChannels(
            """
            #EXTM3U
            #EXTGRP:孤立分组
            #EXTINF:-1 group-title="正常分组" tvg-name="测试台",测试台
            https://live.test/a.m3u8
            """.trimIndent(),
        )

        val channel = channels.single()
        assertEquals("测试台", channel.name)
        assertEquals("正常分组", channel.groupName)
        assertEquals("https://live.test/a.m3u8", channel.lines.single().url)
    }
}
