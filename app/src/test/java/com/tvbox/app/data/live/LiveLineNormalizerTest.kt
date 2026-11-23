package com.tvbox.app.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路修复的回归测试：这些缺陷都实测确认过，是会直接导致「无法连接」的。
 */
class LiveLineNormalizerTest {

    // ── codec 必须唯一：重复拼接会让 CDN 返回 HTML ──────────────

    @Test
    fun `codec parameter is set exactly once for huya flv`() {
        val url = "http://al.flv.huya.com/src/abc.flv?wsSecret=s&wsTime=t&fm=f"
        val fixed = LiveLineNormalizer.normalizeUrl("huya", "flv", url)!!
        assertEquals("codec 只能出现一次", 1, Regex("[?&]codec=").findAll(fixed).count())
        assertTrue(fixed.contains("codec=264"))
    }

    @Test
    fun `existing codec is replaced instead of appended`() {
        // 实测：在已带 codec 的地址后再追加 &codec=264，CDN 直接返回 HTML。
        val url = "http://al.flv.huya.com/src/abc.flv?wsSecret=s&codec=265&ratio=6000"
        val fixed = LiveLineNormalizer.normalizeUrl("huya", "flv", url)!!
        assertEquals(1, Regex("[?&]codec=").findAll(fixed).count())
        assertTrue(fixed.contains("codec=264"))
        assertTrue("其它参数必须保留", fixed.contains("ratio=6000"))
        assertTrue(fixed.contains("wsSecret=s"))
    }

    @Test
    fun `duplicate query keys are collapsed`() {
        val url = "http://h.flv.huya.com/src/a.flv?ratio=6000&ratio=4000&wsTime=t"
        val fixed = LiveLineNormalizer.normalizeUrl("huya", "flv", url)!!
        assertEquals(1, Regex("[?&]ratio=").findAll(fixed).count())
    }

    @Test
    fun `hls urls are not touched`() {
        val url = "https://x.huya.com/src/a.m3u8?wsSecret=s"
        assertEquals(url, LiveLineNormalizer.normalizeUrl("huya", "hls", url))
    }

    // ── 候选去重：避免「移动端」与 PC 候选是同一路流 ────────────

    @Test
    fun `identical candidates are deduped keeping order`() {
        val list = listOf(
            "AL" to "http://al.flv.huya.com/src/a.flv?wsSecret=s1&wsTime=t",
            "TX" to "http://tx.flv.huya.com/src/a.flv?wsSecret=s2&wsTime=t",
            // 与第一条完全相同（解析层字符串替换失效后的真实形态）。
            "移动端" to "http://al.flv.huya.com/src/a.flv?wsSecret=s1&wsTime=t",
        )
        val out = LiveLineNormalizer.normalizeCandidates("huya", "flv", list)
        assertEquals(2, out.size)
        assertEquals("AL", out[0].first)
        assertEquals("TX", out[1].first)
    }

    @Test
    fun `invalid urls are dropped`() {
        val out = LiveLineNormalizer.normalizeCandidates(
            "huya", "flv",
            listOf("坏" to "not-a-url", "空" to "", "好" to "http://a.flv.huya.com/x.flv?wsSecret=s"),
        )
        assertEquals(1, out.size)
        assertEquals("好", out[0].first)
    }

    // ── 请求头必须与签名里的 ctype/t 匹配 ───────────────────────

    @Test
    fun `wap signed urls get the mobile user agent and referer`() {
        // 页面 antiCode 实际就是 tars_mobile/t=103，Go 侧的 ReplaceAll 匹配不到。
        val url = "http://al.flv.huya.com/src/a.flv?ctype=tars_mobile&t=103&wsSecret=s"
        val headers = LiveLineNormalizer.headersFor("huya", url, emptyMap())
        assertEquals(LiveLineNormalizer.HUYA_MOBILE_UA, headers["User-Agent"])
        assertTrue(headers["Referer"]!!.startsWith("https://m.huya.com"))
    }

    @Test
    fun `pc signed urls get the hysdk user agent`() {
        val url = "http://al.flv.huya.com/src/a.flv?ctype=huya_pc_exe&t=0&wsSecret=s"
        val headers = LiveLineNormalizer.headersFor("huya", url, emptyMap())
        assertEquals(LiveLineNormalizer.HUYA_PC_UA, headers["User-Agent"])
        assertTrue(headers["Referer"]!!.startsWith("https://www.huya.com"))
    }

    @Test
    fun `non huya platforms keep their headers untouched`() {
        val base = mapOf("Referer" to "https://m.douyu.com/1", "User-Agent" to "UA")
        assertEquals(base, LiveLineNormalizer.headersFor("douyu", "https://x.flv?a=1", base))
    }

    // ── 协议必须逐条推断：顶层 protocol 只代表第一条线路 ───────

    @Test
    fun `protocol is inferred per candidate url`() {
        assertEquals("hls", LiveLineNormalizer.inferProtocol("https://x.douyu.com/a.m3u8?key=v", "flv"))
        assertEquals("flv", LiveLineNormalizer.inferProtocol("https://x.douyu.com/a.flv?key=v", "hls"))
        assertEquals("hls", LiveLineNormalizer.inferProtocol("https://x.douyu.com/live?fmt=m3u8", "flv"))
        assertEquals("hls", LiveLineNormalizer.inferProtocol("https://x.douyu.com/live", "hls"))
        assertEquals("flv", LiveLineNormalizer.inferProtocol("https://x.douyu.com/live", ""))
    }

    @Test
    fun `mixed protocol candidates keep their own protocol after normalization`() {
        val out = LiveLineNormalizer.normalizeCandidates(
            "douyu",
            listOf(
                Triple("HLS", "hls", "https://x.douyu.com/a.m3u8?wsSecret=s"),
                Triple("FLV", "flv", "http://x.flv.douyu.com/b.flv?wsSecret=s1&wsSecret=s2"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals("hls", out[0].second)
        assertEquals("flv", out[1].second)
        // FLV 线路仍要做去重修复，HLS 线路保持原样。
        assertEquals(1, Regex("wsSecret=").findAll(out[1].third).count())
        assertEquals("https://x.douyu.com/a.m3u8?wsSecret=s", out[0].third)
    }

    @Test
    fun `legacy pair overload still shares one protocol`() {
        val out = LiveLineNormalizer.normalizeCandidates(
            "huya", "flv", listOf("A" to "http://a.flv.huya.com/x.flv?wsSecret=s"),
        )
        assertEquals(1, out.size)
        assertEquals("A", out[0].first)
    }
}
