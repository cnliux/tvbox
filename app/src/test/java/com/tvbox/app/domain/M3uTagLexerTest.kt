package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2-15：EXTINF 状态机 + 频道/线路拆分的 JVM 单测。 */
class M3uTagLexerTest {

    @Test
    fun `attribute lexer keeps pipe and escaped quotes intact`() {
        val attrs = M3uTagLexer.parseAttributes(
            """-1 tvg-id="CCTV1" tvg-name="CCTV\"1" group-title="央视|高清",CCTV-1 高清""",
        )
        assertEquals("CCTV1", attrs["tvg-id"])
        assertEquals("CCTV\"1", attrs["tvg-name"])
        assertEquals("央视|高清", attrs["group-title"])
        assertEquals("CCTV-1 高清", attrs["name"])
    }

    @Test
    fun `duplicate channel folds into one channel with five lines`() {
        val input = buildList {
            add("#EXTM3U")
            repeat(5) { i ->
                add("#EXTINF:-1 tvg-id=\"CCTV1\" tvg-name=\"CCTV-1\" group-title=\"央视\",CCTV-1")
                add("https://cdn$i.example.com/live.m3u8")
            }
        }
        val result = M3uTagLexer.parse(input)
        assertEquals(1, result.channels.size)
        assertEquals("cctv1", result.channels.first().key)
        assertEquals(5, result.lines.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `bom and fullwidth spaces do not break identity`() {
        val input = listOf(
            "\uFEFF#EXTM3U",
            "#EXTINF:-1 tvg-id=\"ＣＣＴＶ　１\" tvg-name=\"演示　频道\",演示 频道",
            "https://a.example.com/1.m3u8",
        )
        val result = M3uTagLexer.parse(input)
        assertEquals(1, result.channels.size)
        assertEquals("cctv 1", result.channels.first().key)
        assertEquals(1, result.lines.size)
    }

    @Test
    fun `missing tvg id falls back to normalized name`() {
        val result = M3uTagLexer.parse(
            listOf(
                "#EXTINF:-1 tvg-name=\"演示频道\",演示频道",
                "https://a.example.com/1.m3u8",
            ),
        )
        assertEquals("演示频道", result.channels.first().key)
        assertNull(result.channels.first().tvgId)
    }

    @Test
    fun `malformed lines are skipped without throwing`() {
        val result = M3uTagLexer.parse(
            listOf(
                "#EXTM3U",
                "#EXTINF:-1,",
                "#EXTINF:-1 tvg-name=\"A\",A",
                "not-a-url",
                "rtmp://live.example.com/stream",
                "",
            ),
        )
        assertEquals(1, result.channels.size)
        assertEquals(1, result.lines.size)
        assertEquals("rtmp://live.example.com/stream", result.lines.first().url)
        assertTrue(result.skipped >= 2)
    }
}
