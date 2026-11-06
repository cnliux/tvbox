package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSourceDryRunTest {

    @Test
    fun `txt source with groups and lines produces full summary`() {
        // 注意：线路名元数据用 '$' 与地址拼接，这里用普通字符串避免 Kotlin 字符串模板。
        val raw = "央视频道,#genre#\n" +
            "CCTV-1,http://a.example.com/1.m3u8\n" +
            "CCTV-2," + "线路1" + "$" + "http://a.example.com/2a.m3u8," +
            "线路2" + "$" + "http://a.example.com/2b.m3u8\n" +
            "卫视频道,#genre#\n" +
            "湖南卫视,http://a.example.com/hn.m3u8"

        val result = dryRunLiveSource(raw)

        assertTrue(result.ok)
        assertEquals("TXT", result.format)
        assertEquals(3, result.channelCount)
        assertEquals(4, result.lineCount)
        assertEquals(2, result.groupCount)
        assertEquals(0, result.channelsWithoutGroup)
        assertEquals(3, result.channelsWithoutLogo)
        assertTrue(result.warnings().any { it.contains("台标") })
    }

    @Test
    fun `txt without group header is counted as missing group`() {
        val raw = "CCTV-1,http://a.example.com/1.m3u8\nCCTV-2,http://a.example.com/2.m3u8"
        val result = dryRunLiveSource(raw)
        assertEquals(2, result.channelCount)
        assertEquals(2, result.channelsWithoutGroup)
        assertTrue(result.warnings().any { it.contains("分组") })
    }

    @Test
    fun `m3u source is detected and parsed`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" tvg-logo="http://logo/1.png" group-title="央视",CCTV-1
            http://a.example.com/1.m3u8
            #EXTINF:-1 tvg-name="CCTV-5" group-title="央视",CCTV-5 高清
            http://a.example.com/5.m3u8
        """.trimIndent()

        val result = dryRunLiveSource(raw)

        assertTrue(result.ok)
        assertEquals("M3U", result.format)
        assertEquals(2, result.channelCount)
        assertEquals(1, result.channelsWithoutLogo)
        assertEquals(0, result.channelsWithoutGroup)
    }

    @Test
    fun `html page is rejected with an explicit message`() {
        val raw = "<!DOCTYPE html>\n<html>\n<body>Directory listing</body>\n</html>"
        val result = dryRunLiveSource(raw)
        assertFalse(result.ok)
        assertTrue(result.htmlPage)
        assertTrue(result.headline().contains("网页"))
    }

    @Test
    fun `blank content is reported as empty`() {
        val result = dryRunLiveSource("   \n  ")
        assertFalse(result.ok)
        assertTrue(result.empty)
        assertEquals("内容为空，无法导入", result.headline())
    }

    @Test
    fun `channel lines without playable address are counted as dropped`() {
        val raw = """
            央视频道,#genre#
            CCTV-1,http://a.example.com/1.m3u8
            CCTV-2,
            CCTV-3,不是地址
        """.trimIndent()

        val result = dryRunLiveSource(raw)

        assertEquals(1, result.channelCount)
        assertEquals(2, result.droppedLines)
        assertTrue(result.warnings().any { it.contains("缺少有效地址") })
    }

    @Test
    fun `m3u extinf without following url is counted as dropped`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1",CCTV-1
            http://a.example.com/1.m3u8
            #EXTINF:-1 tvg-name="CCTV-2",CCTV-2
            #EXTINF:-1 tvg-name="CCTV-3",CCTV-3
            http://a.example.com/3.m3u8
        """.trimIndent()

        val result = dryRunLiveSource(raw)
        assertEquals(2, result.channelCount)
        assertEquals(1, result.droppedLines)
    }

    @Test
    fun `headline carries the counts when the source is usable`() {
        val result = dryRunLiveSource("CCTV-1,http://a.example.com/1.m3u8")
        assertTrue(result.headline().contains("1 个频道"))
        assertTrue(result.headline().contains("TXT"))
    }
}
