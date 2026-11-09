package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 直播线路 MIME 类型探测的回归锁定：
 * - Content-Type 头的各类媒体类型映射到正确常量。
 * - 正文头部特征（m3u8 / FLV 签名 / MPEG-TS 同步字节）识别无误。
 * - 空/极短 buffer、未知内容类型不崩溃，返回 LIVE_MIME_UNKNOWN。
 */
class LiveStreamProbeTest {

    @Test
    fun mimeFromContentTypeRecognizesMpegurlVariants() {
        assertEquals(LIVE_MIME_M3U8, mimeFromContentType("application/x-mpegURL"))
        assertEquals(LIVE_MIME_M3U8, mimeFromContentType("application/vnd.apple.mpegurl"))
        assertEquals(LIVE_MIME_M3U8, mimeFromContentType("text/plain; charset=utf-8; mpegurl=detect"))
    }

    @Test
    fun mimeFromContentTypeRecognizesFlv() {
        assertEquals(LIVE_MIME_FLV, mimeFromContentType("video/x-flv"))
        assertEquals(LIVE_MIME_FLV, mimeFromContentType("application/octet-stream; profile=x-flv"))
    }

    @Test
    fun mimeFromContentTypeRecognizesMp2t() {
        assertEquals(LIVE_MIME_MP2T, mimeFromContentType("video/mp2t"))
        assertEquals(LIVE_MIME_MP2T, mimeFromContentType("video/mp2ts"))
    }

    @Test
    fun mimeFromContentTypeReturnsNullForUnknownType() {
        assertNull(mimeFromContentType("application/json"))
        assertNull(mimeFromContentType("text/html"))
        // 函数签名 ContentType: String（非空），空字符串也返回 null。
        assertNull(mimeFromContentType(""))
    }

    @Test
    fun mimeFromBodyHeadDetectsM3u8ByExtmark() {
        val data = "#EXTM3U\n#EXTINF:-1,\nhttps://live.test/ch.m3u8".toByteArray(Charsets.UTF_8)
        assertEquals(LIVE_MIME_M3U8, mimeFromBodyHead(data, data.size))
        // 前置 BOM / 空白不应破坏检测（trimStart 去掉零宽字符、空格、制表符、回车、换行）。
        val withBom = ByteArray(3 + data.size).apply {
            set(0, 0xEF.toByte()); set(1, 0xBB.toByte()); set(2, 0xBF.toByte())
            data.copyInto(this, destinationOffset = 3)
        }
        assertEquals(LIVE_MIME_M3U8, mimeFromBodyHead(withBom, withBom.size))
        val padded = ByteArray(2 + data.size).apply {
            set(0, ' '.code.toByte()); set(1, '\t'.code.toByte())
            data.copyInto(this, destinationOffset = 2)
        }
        assertEquals(LIVE_MIME_M3U8, mimeFromBodyHead(padded, padded.size))
    }

    @Test
    fun mimeFromBodyHeadDetectsFlvSignature() {
        // FLV 文件头：46 4C 56 = "FLV"。
        val flvHeader = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00)
        assertEquals(LIVE_MIME_FLV, mimeFromBodyHead(flvHeader, flvHeader.size))
        // 不足 3 字节不能确认 FLV。
        assertEquals(LIVE_MIME_UNKNOWN, mimeFromBodyHead(byteArrayOf('F'.code.toByte(), 'L'.code.toByte()), 2))
    }

    @Test
    fun mimeFromBodyHeadDetectsTsSyncByte() {
        // TS：首字节 0x47；长度 <= 188 时不再读第二包，直接判定。
        val tsShort = ByteArray(100).apply { this[0] = 0x47.toByte() }
        assertEquals(LIVE_MIME_MP2T, mimeFromBodyHead(tsShort, tsShort.size))
        // 长度 > 188 时要求第 189 字节也是 0x47。
        val tsLong = ByteArray(300).apply {
            this[0] = 0x47.toByte()
            this[188] = 0x47.toByte()
        }
        assertEquals(LIVE_MIME_MP2T, mimeFromBodyHead(tsLong, tsLong.size))
        // 第 189 字节不是 0x47 → 不可判定。
        val tsFake = ByteArray(300).apply { this[0] = 0x47.toByte(); this[188] = 0x00.toByte() }
        assertEquals(LIVE_MIME_UNKNOWN, mimeFromBodyHead(tsFake, tsFake.size))
    }

    @Test
    fun mimeFromBodyHeadRejectsEmptyOrZeroLengthBuffer() {
        assertEquals(LIVE_MIME_UNKNOWN, mimeFromBodyHead(ByteArray(0), 0))
        assertEquals(LIVE_MIME_UNKNOWN, mimeFromBodyHead(ByteArray(10), 0))
    }

    @Test
    fun mimeFromBodyHeadReturnsUnknownForRandomBytes() {
        val random = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals(LIVE_MIME_UNKNOWN, mimeFromBodyHead(random, random.size))
    }
}
