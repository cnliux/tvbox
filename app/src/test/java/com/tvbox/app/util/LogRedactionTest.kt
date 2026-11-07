package com.tvbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {

    @Test
    fun `http 地址只保留协议与主机`() {
        assertEquals(
            "https://example.com",
            logSafeUrl("https://example.com/live/cctv1.m3u8?token=secret&expire=123"),
        )
    }

    @Test
    fun `非默认端口保留，默认端口省略`() {
        assertEquals("rtmp://10.0.0.2", logSafeUrl("rtmp://10.0.0.2:1935/live/stream"))
        assertEquals("rtmp://10.0.0.2:1936", logSafeUrl("rtmp://10.0.0.2:1936/live/stream"))
        assertEquals("http://192.168.1.7", logSafeUrl("http://192.168.1.7:80/live.m3u"))
        assertEquals("https://cdn.example.com", logSafeUrl("https://cdn.example.com:443/a/b.m3u8"))
    }

    @Test
    fun `地址中的路径与 query 不会出现在结果里`() {
        val raw = "http://user:pass@source.example.com:8080/iptv/index.m3u?token=abc#frag"
        val safe = logSafeUrl(raw)
        assertFalse(safe.contains("token"))
        assertFalse(safe.contains("index.m3u"))
        assertFalse(safe.contains("pass"))
        assertEquals("http://source.example.com:8080", safe)
    }

    @Test
    fun `无协议地址退化为 hash 且保持稳定`() {
        val safe = logSafeUrl("192.168.1.7/live/1.m3u8")
        assertTrue(safe.startsWith("hash:"))
        assertEquals(safe, logSafeUrl("192.168.1.7/live/1.m3u8"))
        assertFalse(safe.contains("192.168.1.7"))
    }

    @Test
    fun `空地址不输出原始内容`() {
        assertEquals("<空>", logSafeUrl(null))
        assertEquals("<空>", logSafeUrl("   "))
    }

    @Test
    fun `异常文本里的地址只保留协议与主机`() {
        val raw = "Source error: https://cdn.example.com/live/cctv1.m3u8?token=secret (PlaybackException)"
        val safe = logSafeText(raw)
        assertFalse(safe.contains("token"))
        assertFalse(safe.contains("cctv1.m3u8"))
        assertEquals("Source error: https://cdn.example.com (PlaybackException)", safe)
    }

    @Test
    fun `文本中多个地址逐处脱敏并保留标点`() {
        val raw = "a http://a.example.com:8080/x?t=1, b rtmp://b.example.com/live/1;"
        val safe = logSafeText(raw)
        assertFalse(safe.contains("/x?t=1"))
        assertFalse(safe.contains("/live/1"))
        assertTrue(safe.contains("http://a.example.com:8080,"))
        assertTrue(safe.contains("rtmp://b.example.com;"))
    }

    @Test
    fun `无地址的异常文本保持原样`() {
        assertEquals("播放器错误：解码失败", logSafeText("播放器错误：解码失败"))
        assertEquals("<空>", logSafeText(null))
    }

    @Test
    fun `请求头只输出名称`() {
        assertEquals(
            "Cookie,Referer",
            logSafeHeaderNames(mapOf("Referer" to "http://a.example.com/x?t=1", "Cookie" to "sid=1")),
        )
        assertEquals("无", logSafeHeaderNames(null))
    }
}
