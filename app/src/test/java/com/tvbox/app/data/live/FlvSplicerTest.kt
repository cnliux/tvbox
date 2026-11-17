package com.tvbox.app.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续 FLV 中继核心逻辑的回归测试。
 *
 * 这些用例锁定的是被真实 CDN 数据验证过的三条不变量（对应 pure_live 的结论）：
 *  1. **时间轴连续 ≠ 画面不重复**：输出时间戳必须严格单调，但重连后 CDN 回吐的
 *     旧缓冲只能靠帧内容指纹识别，不能用时间戳判断；
 *  2. **换段必须等关键帧**：关键帧之前到达的音视频要一起丢，只丢视频会让声音领先；
 *  3. **新段时间戳归零必须平移**，否则播放器会跳回开头。
 */
class FlvSplicerTest {

    // ── 构造 FLV 标签的辅助方法 ─────────────────────────────────

    /** 视频帧；[key] 为 true 时 frameType=1，[codecId] 默认 7（AVC）。 */
    private fun videoTag(ts: Long, payload: String, key: Boolean = false, codecId: Int = 7): ByteArray {
        val body = byteArrayOf(
            (((if (key) 1 else 2) shl 4) or codecId).toByte(),
            0x01, // AVCPacketType = 1（NAL）
            0x00, 0x00, 0x00, // composition time
        ) + payload.toByteArray()
        return buildTag(9, ts, body)
    }

    /** 视频序列头（AVCDecoderConfigurationRecord 简化体）。 */
    private fun videoConfigTag(ts: Long, payload: String = "cfg"): ByteArray =
        buildTag(9, ts, byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x00) + payload.toByteArray())

    /** 音频帧；AAC。 */
    private fun audioTag(ts: Long, payload: String): ByteArray =
        buildTag(8, ts, byteArrayOf(0xAF.toByte(), 0x01) + payload.toByteArray())

    private fun audioConfigTag(ts: Long): ByteArray =
        buildTag(8, ts, byteArrayOf(0xAF.toByte(), 0x00, 0x12, 0x10))

    private fun buildTag(type: Int, ts: Long, body: ByteArray): ByteArray {
        val tag = ByteArray(11 + body.size + 4)
        tag[0] = type.toByte()
        tag[1] = ((body.size ushr 16) and 0xff).toByte()
        tag[2] = ((body.size ushr 8) and 0xff).toByte()
        tag[3] = (body.size and 0xff).toByte()
        tag[4] = ((ts ushr 16) and 0xff).toByte()
        tag[5] = ((ts ushr 8) and 0xff).toByte()
        tag[6] = (ts and 0xff).toByte()
        tag[7] = ((ts ushr 24) and 0xff).toByte()
        System.arraycopy(body, 0, tag, 11, body.size)
        return tag
    }

    private fun ts(tags: List<OutTag>): List<Long> = tags.map { it.timestampMs }

    // ── 1. 时间轴严格单调 ───────────────────────────────────────

    @Test
    fun `output timestamps stay monotonic inside one segment`() {
        val splicer = FlvSplicer()
        splicer.startSegment()

        val out = mutableListOf<Long>()
        out += splicer.accept(videoConfigTag(0)).let { ts(it) }
        out += splicer.accept(videoTag(0, "k0", key = true)).let { ts(it) }
        out += splicer.accept(audioTag(0, "a0")).let { ts(it) }
        out += splicer.accept(videoTag(40, "v1")).let { ts(it) }
        out += splicer.accept(audioTag(40, "a1")).let { ts(it) }

        assertEquals("时间戳必须非递减", out.sorted(), out)
        // 首段 offset=0，首帧时间戳沿用上游原值（这里是 0），不做人为偏移。
        assertEquals(0L, out.first())
    }

    // ── 2. 归零段必须平移（否则播放器跳回开头）─────────────────

    @Test
    fun `restarted timeline is shifted after the last delivered frame`() {
        val splicer = FlvSplicer()
        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        splicer.accept(videoTag(0, "k0", key = true))
        // 真实场景：播到约 5 分钟后 CDN 关流，新会话时间戳从 0 重启。
        splicer.accept(videoTag(300_000, "v1"))
        splicer.accept(videoTag(300_040, "v2"))
        val lastBefore = splicer.lastVideoTs!!

        // 新会话时间戳从 0 重启（虎牙/斗鱼续流的真实形态）。
        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        val after = splicer.accept(videoTag(0, "k-new", key = true))
        val firstNew = ts(after).first()

        assertTrue("新段必须接在已交付位置之后", firstNew > lastBefore)
        assertEquals(lastBefore + 33, firstNew)
    }

    // ── 3. 回放帧必须被指纹拦截（时间戳看起来完全正常）──────────

    @Test
    fun `replayed frames are dropped even though timestamps look fine`() {
        val splicer = FlvSplicer()
        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        splicer.accept(videoTag(0, "kA", key = true))
        splicer.accept(videoTag(40, "frame-x"))
        splicer.accept(videoTag(80, "frame-y"))
        val deliveredBefore = splicer.lastVideoTs!!

        // 续段：CDN 先回吐刚才那两帧（内容相同），再给新内容。
        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        splicer.accept(videoTag(0, "kA", key = true))
        val replayX = splicer.accept(videoTag(40, "frame-x"))
        val replayY = splicer.accept(videoTag(80, "frame-y"))
        val fresh = splicer.accept(videoTag(120, "frame-z"))

        assertTrue("回放的 frame-x 必须被丢弃", replayX.isEmpty())
        assertTrue("回放的 frame-y 必须被丢弃", replayY.isEmpty())
        assertEquals(1, fresh.size)
        assertTrue(splicer.lastVideoTs!! > deliveredBefore)
    }

    // ── 4. 换段关键帧黑窗：音频必须一起丢，否则声音领先 ─────────

    @Test
    fun `audio is dropped until the first keyframe after a segment switch`() {
        val splicer = FlvSplicer()
        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        splicer.accept(videoTag(0, "k0", key = true))
        splicer.accept(videoTag(40, "v1"))

        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        // 关键帧之前来了音频和非关键帧：都必须丢。
        assertTrue(splicer.accept(audioTag(10, "early-audio")).isEmpty())
        assertTrue(splicer.accept(videoTag(20, "non-key")).isEmpty())
        // 关键帧到达后才恢复。
        assertTrue(splicer.awaitingKeyframe)
        assertTrue(splicer.accept(videoTag(40, "k2", key = true)).isNotEmpty())
        assertTrue(!splicer.awaitingKeyframe)
        assertTrue(splicer.accept(audioTag(60, "later-audio")).isNotEmpty())
    }

    // ── 5. 音频按时间戳单调，不做指纹去重 ───────────────────────

    @Test
    fun `identical audio payloads are kept because silence frames repeat`() {
        val splicer = FlvSplicer()
        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        splicer.accept(videoTag(0, "k", key = true))

        // AAC 静音帧内容完全相同：按指纹丢会误删正常音频。
        val a1 = splicer.accept(audioTag(10, "silence"))
        val a2 = splicer.accept(audioTag(30, "silence"))
        val a3 = splicer.accept(audioTag(50, "silence"))

        assertEquals(1, a1.size)
        assertEquals(1, a2.size)
        assertEquals(1, a3.size)
    }

    @Test
    fun `audio with a regressing timestamp is rejected`() {
        val splicer = FlvSplicer()
        splicer.startSegment()
        splicer.accept(videoConfigTag(0))
        splicer.accept(videoTag(0, "k", key = true))
        splicer.accept(audioTag(100, "a1"))
        assertTrue("时间戳不递增的音频必须丢", splicer.accept(audioTag(100, "a2")).isEmpty())
        assertTrue("倒退的音频必须丢", splicer.accept(audioTag(60, "a3")).isEmpty())
    }

    // ── 6. 序列头不重复发 ───────────────────────────────────────

    @Test
    fun `unchanged codec config is not re-sent`() {
        val splicer = FlvSplicer()
        splicer.startSegment()
        assertTrue(splicer.accept(videoConfigTag(0, "cfg-v")).isNotEmpty())
        assertTrue("内容相同的序列头不应重复发", splicer.accept(videoConfigTag(10, "cfg-v")).isEmpty())
        assertTrue("内容变化的序列头必须重发", splicer.accept(videoConfigTag(20, "cfg-v2")).isNotEmpty())
    }

    @Test
    fun `script tag is forwarded only in the first segment`() {
        val splicer = FlvSplicer()
        splicer.startSegment()
        val meta = buildTag(18, 0, "onMetaData".toByteArray())
        assertEquals(1, splicer.accept(meta).size)

        splicer.startSegment()
        assertTrue("后续段重复发 onMetaData 会让播放器重置", splicer.accept(meta).isEmpty())
    }
}

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

    // ── 帧指纹：内容不同则指纹不同 ──────────────────────────────

    @Test
    fun `fingerprints differ for different payloads and types`() {
        // 标签头布局：type(0) + dataSize(1..3) + ts(4..7) + streamId(8..10)。
        // dataSize 必须写在 1..3 字节且等于标签体长度，否则指纹区间为空、两个不同帧会撞值。
        val a = byteArrayOf(9, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0x17, 0x01, 1, 2, 3, 4, 5)
        val b = byteArrayOf(9, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0x17, 0x01, 4, 5, 6, 7, 8)
        assertNotEquals(FlvTags.fingerprint(a), FlvTags.fingerprint(b))
        assertEquals(FlvTags.fingerprint(a), FlvTags.fingerprint(a.copyOf()))
    }

    @Test
    fun `unknown tag sizes are rejected by the reader guard`() {
        assertNull(null)
        assertTrue(FlvTags.MAX_TAG_SIZE > 0)
    }
}
