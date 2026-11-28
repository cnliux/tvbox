package com.tvbox.app.data

import com.tvbox.app.domain.parseXmltvEpg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * EPG 仓库的边界回归：非法 URL 与空输入必须降级为空节目单，
 * 绝不能抛异常或发起网络请求（EPG 是增强项，不阻塞直播主流程）。
 */
class RemoteEpgRepositoryTest {

    private val repository = RemoteEpgRepository()

    @Test
    fun blankUrlReturnsEmptyMap() = runBlocking {
        assertTrue(repository.fetch("").isEmpty())
        assertTrue(repository.fetch("   ").isEmpty())
    }

    @Test
    fun nonHttpUrlReturnsEmptyMapWithoutNetwork() = runBlocking {
        assertTrue(repository.fetch("ftp://example.com/epg.xml").isEmpty())
        assertTrue(repository.fetch("file:///sdcard/epg.xml").isEmpty())
        assertTrue(repository.fetch("not-a-url").isEmpty())
    }

    // ---- 老张 EPG (.xml.gz) 解压回归：Byte 有符号导致 magic 永远不匹配 ----

    private val sampleXmltv = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="老张的EPG">
          <channel id="1"><display-name lang="zh">CCTV1</display-name></channel>
          <programme start="20261128000700 +0800" stop="20261128003800 +0800" channel="1">
            <title>新闻联播</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
        out.toByteArray()
    }

    @Test
    fun gzipMagicUsesUnsignedByteComparison() {
        // 0x8B 作为有符号 Byte 是 -117：直接 toInt()==0x8B 会永远 false。
        val zipped = gzip(sampleXmltv.toByteArray(Charsets.UTF_8))
        assertEquals(0x1F, zipped[0].toInt() and 0xFF)
        assertEquals(0x8B, zipped[1].toInt() and 0xFF)
        assertTrue("gzip magic 必须被识别", isGzipEpgPayload(zipped))
        assertFalse("明文 XML 不应误判为 gzip", isGzipEpgPayload(sampleXmltv.toByteArray(Charsets.UTF_8)))
        assertFalse("空数据不应误判为 gzip", isGzipEpgPayload(ByteArray(0)))
    }

    @Test
    fun gzipPayloadDecodesToParsableXmltv() {
        val zipped = gzip(sampleXmltv.toByteArray(Charsets.UTF_8))
        val decoded = decodeEpgPayload(zipped)
        assertEquals(sampleXmltv, decoded)
        val programs = parseXmltvEpg(decoded)
        assertEquals(1, programs["CCTV1"].orEmpty().size)
        assertEquals("新闻联播", programs["CCTV1"].orEmpty().first().title)
    }

    @Test
    fun gzipPayloadWithUtf8BomDecodesToParsableXmltv() {
        // 真实源常带 UTF-8 BOM；BOM 三字节同样是有符号 Byte，必须按无符号比较剥离。
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            sampleXmltv.toByteArray(Charsets.UTF_8)
        val decoded = decodeEpgPayload(gzip(withBom))
        assertFalse("BOM 应被剥离", decoded.startsWith('\uFEFF'))
        assertEquals(sampleXmltv, decoded)
        val programs = parseXmltvEpg(decoded)
        assertEquals(1, programs["CCTV1"].orEmpty().size)
    }
}
