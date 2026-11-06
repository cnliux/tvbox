package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelNumberSearchTest {

    private fun channel(number: Int, name: String = "CH$number", lines: Int = 1) = LiveChannel(
        number = number,
        groupName = "央视",
        name = name,
        lines = List(lines) { LiveChannelLine(name = "线$it", url = "http://example.com/$it.m3u8") },
    )

    private val channels = listOf(
        channel(1, "CCTV-1"),
        channel(2, "CCTV-2"),
        channel(3, "CCTV-3"),
        channel(10, "湖南卫视"),
        channel(11, "浙江卫视"),
        channel(20, "东方卫视"),
    )

    @Test
    fun `empty input returns no matches`() {
        assertTrue(findLiveChannelsByNumberPrefix(channels, "").isEmpty())
        assertTrue(findLiveChannelsByNumberPrefix(channels, "   ").isEmpty())
    }

    @Test
    fun `prefix one matches every channel starting with 1`() {
        val matches = findLiveChannelsByNumberPrefix(channels, "1")
        assertEquals(listOf(1, 10, 11), matches.map { it.number })
        // index 必须指向完整频道列表中的真实下标，切台时直接使用。
        assertEquals(listOf(0, 3, 4), matches.map { it.index })
    }

    @Test
    fun `more digits narrow the result`() {
        assertEquals(listOf(11), findLiveChannelsByNumberPrefix(channels, "11").map { it.number })
    }

    @Test
    fun `no match returns empty rather than throwing`() {
        assertTrue(findLiveChannelsByNumberPrefix(channels, "9").isEmpty())
    }

    @Test
    fun `non digit input is rejected`() {
        assertTrue(findLiveChannelsByNumberPrefix(channels, "1a").isEmpty())
    }

    @Test
    fun `limit caps the result size`() {
        val many = (1..200).map { channel(it) }
        assertEquals(50, findLiveChannelsByNumberPrefix(many, "1").size)
        assertEquals(3, findLiveChannelsByNumberPrefix(many, "1", limit = 3).size)
    }

    @Test
    fun `exact number wins over prefix match`() {
        // 输入 "1" 时 1 号台精确命中，不该被"第一个前缀匹配"含糊掉。
        assertEquals(0, resolveLiveChannelNumberTarget(channels, "1"))
        assertEquals(3, resolveLiveChannelNumberTarget(channels, "10"))
    }

    @Test
    fun `unknown exact falls back to first prefix match`() {
        // 输入 "12" 没有 12 号台，回退到第一个 12 开头的频道（这里没有 → -1）。
        assertEquals(-1, resolveLiveChannelNumberTarget(channels, "12"))
        val padded = channels + channel(120, "测试台")
        assertEquals(padded.lastIndex, resolveLiveChannelNumberTarget(padded, "12"))
    }

    @Test
    fun `out of range number resolves to nothing`() {
        assertEquals(-1, resolveLiveChannelNumberTarget(channels, "99"))
        assertEquals(-1, resolveLiveChannelNumberTarget(channels, "abc"))
    }
}
