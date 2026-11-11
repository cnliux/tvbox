package com.tvbox.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 弹幕 Tars 编解码回归：虎牙进房包与推送解析都依赖这套极简实现，
 * 一旦 tag/type 编码错位，弹幕会静默失效（没有异常，只是收不到消息）。
 */
class DanmakuProtocolTest {

    @Test
    fun `零值使用 ZERO_TAG 编码`() {
        val writer = TarsWriter()
        writer.writeInt(0, 0)
        assertArrayEquals(byteArrayOf(0x0C), writer.toByteArray())
    }

    @Test
    fun `小整数使用单字节编码`() {
        val writer = TarsWriter()
        writer.writeInt(127, 0)
        assertArrayEquals(byteArrayOf(0x00, 0x7F), writer.toByteArray())
    }

    @Test
    fun `字符串按长度前缀编码`() {
        val writer = TarsWriter()
        writer.writeString("ab", 3)
        assertArrayEquals(byteArrayOf(0x36, 0x02, 0x61, 0x62), writer.toByteArray())
    }

    @Test
    fun `结构体可以按 tag 还原`() {
        val inner = TarsWriter().apply {
            writeInt(1234, 0)
            writeString("主播", 2)
            writeInt(0, 3)
        }.toByteArray()
        val packet = TarsWriter().apply {
            writeInt(1, 0)
            writeBytes(inner, 1)
        }.toByteArray()

        val top = TarsReader(packet).readStruct()
        assertEquals(1L, top[0])

        val nested = TarsReader(top[1] as ByteArray).readStruct()
        assertEquals(1234L, nested[0])
        assertEquals("主播", nested[2])
        // 0 走 ZERO_TAG：只占一个头字节，读回来仍是 0。
        assertEquals(0L, nested[3])
    }

    @Test
    fun `大整数使用四字节编码`() {
        val writer = TarsWriter()
        writer.writeInt(100_000, 4)
        val decoded = TarsReader(writer.toByteArray()).readStruct()
        assertEquals(100_000L, decoded[4])
    }

    @Test
    fun `字节数组长度字段的 tag 为 0`() {
        val writer = TarsWriter()
        writer.writeBytes(byteArrayOf(1, 2), 1)
        val bytes = writer.toByteArray()
        // head(13,1) + head(0,0) + 长度(int8, tag 0) + 两个字节
        assertArrayEquals(byteArrayOf(0x1D, 0x00, 0x00, 0x02, 0x01, 0x02), bytes)
        val decoded = TarsReader(bytes).readStruct()[1] as ByteArray
        assertArrayEquals(byteArrayOf(1, 2), decoded)
    }

    @Test
    fun `字符串列表按 tars LIST 编码并可还原`() {
        val writer = TarsWriter()
        writer.writeStringList(listOf("live:1", "chat:1"), 0)
        val decoded = TarsReader(writer.toByteArray()).readStruct()[0] as List<*>
        assertEquals(listOf("live:1", "chat:1"), decoded)
    }
}
