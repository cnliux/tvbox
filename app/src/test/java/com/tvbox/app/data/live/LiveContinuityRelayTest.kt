package com.tvbox.app.data.live

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * **直接驱动真实中继代码**的端到端测试（JVM，本地假 CDN）。
 *
 * 为什么要有它：单测只验证 `FlvSplicer` 的子逻辑，无法证明
 * 「上游被 CDN 掐断 → 中继自动续拉 → 播放器侧时间轴仍然连续」这条主链路真的成立。
 * 这里起一个真实的 [LiveContinuityRelay]，让它连本地假 CDN，
 * 再用一个 HTTP 客户端模拟播放器读本地流，全部走真实的 Socket 与 IO。
 *
 * 假 CDN 复现的是虎牙/斗鱼实测出的三个形态：
 *  1. 每个会话约 2 秒就被服务端**正常关闭**（真实是 120s，这里压缩以便快速跑多轮）；
 *  2. 新会话时间戳**从 0 重启**；
 *  3. 新会话先**回吐**上一段最后两帧（真实实测回放 6.08s，这里压缩为 2 帧）。
 *
 * 断言（就是用户关心的三件事）：时间戳全程单调、回放帧被丢弃、播放器不被断开。
 */
class LiveContinuityRelayTest {

    private val sessions = AtomicInteger(0)

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

    /** 一段会话的完整字节：FLV 头 + 视频序列头 + 关键帧 + 若干帧。 */
    private fun segment(sessionNo: Int, frames: Int, replayFrames: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x46, 0x4C, 0x56, 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0))
        // 时间戳每段都从 0 重启（真实续流形态）。
        out.write(buildTag(9, 0, byteArrayOf(0x17, 0x00, 0, 0, 0, 0x01, 0x64, 0x00, 0x1E)))
        out.write(buildTag(9, 0, byteArrayOf(0x17, 0x01, 0, 0, 0) + "key-$sessionNo".toByteArray()))
        // 回放的旧帧：内容与上一段完全一致。
        replayFrames.forEachIndexed { index, payload ->
            out.write(buildTag(9, 40L * (index + 1), byteArrayOf(0x27, 0x01, 0, 0, 0) + payload))
        }
        for (i in 1..frames) {
            val ts = 40L * (replayFrames.size + i)
            val body = byteArrayOf(0x27, 0x01, 0, 0, 0) + "s$sessionNo-f$i".toByteArray()
            out.write(buildTag(9, ts, body))
            out.write(buildTag(8, ts, byteArrayOf(0xAF.toByte(), 0x01) + "a$sessionNo-$i".toByteArray()))
        }
        return out.toByteArray()
    }

    @Test
    fun `relay keeps one continuous monotonic stream across cdn cutoffs`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        // 记录每段最后两帧，供下一段「回放」。
        var previousTail: List<ByteArray> = emptyList()

        server.createContext("/live.flv") { exchange: HttpExchange ->
            val no = sessions.incrementAndGet()
            val replay = previousTail
            val body = segment(no, frames = 12, replayFrames = replay)
            previousTail = (1..2).map { "s$no-f${12 - 2 + it}".toByteArray() }
            exchange.responseHeaders.add("Content-Type", "video/x-flv")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            // 写完立刻关闭：模拟 CDN 到点正常关流。
            exchange.close()
        }
        server.start()
        val cdnUrl = "http://127.0.0.1:${server.address.port}/live.flv"

        val relay = LiveContinuityRelay.start(
            site = "douyu",          // 走纯 EOF 续拉路径，不触发虎牙计划预建
            roomId = "test",
            line = LiveLine(url = cdnUrl, cdn = "fake"),
            renew = { LiveLine(url = cdnUrl, cdn = "fake") },
        )
        checkNotNull(relay) { "中继必须能启动" }

        try {
            val connection = java.net.URL(relay.localUrl()).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 20_000
            val input = java.io.DataInputStream(connection.inputStream)

            // FLV 头
            val header = ByteArray(13)
            input.readFully(header)
            assertEquals('F'.code.toByte(), header[0])

            val timestamps = mutableListOf<Long>()
            val payloads = mutableSetOf<String>()
            var replaySeen = 0
            val deadline = System.currentTimeMillis() + 20_000

            while (System.currentTimeMillis() < deadline) {
                val tagHeader = ByteArray(11)
                input.readFully(tagHeader)
                val type = tagHeader[0].toInt() and 0x1f
                val size = ((tagHeader[1].toInt() and 0xff) shl 16) or
                    ((tagHeader[2].toInt() and 0xff) shl 8) or (tagHeader[3].toInt() and 0xff)
                val ts = (((tagHeader[4].toInt() and 0xff) shl 16) or
                    ((tagHeader[5].toInt() and 0xff) shl 8) or
                    (tagHeader[6].toInt() and 0xff)).toLong() or
                    ((tagHeader[7].toInt() and 0xff).toLong() shl 24)
                val body = ByteArray(size)
                input.readFully(body)
                input.skip(4L) // PreviousTagSize
                if (type != 9) continue
                val text = String(body, 5, body.size - 5)
                if (text.startsWith("key-") || text.isEmpty()) continue
                // 回放帧的 payload 已在上一段出现过；中继必须已经丢弃它们。
                if (payloads.contains(text)) replaySeen++
                payloads.add(text)
                timestamps.add(ts)
                if (sessions.get() >= 3 && timestamps.size > 25) break
            }

            assertTrue("必须真的发生多次续流，实际 ${sessions.get()} 段", sessions.get() >= 2)
            assertTrue("必须收到足够的帧，实际 ${timestamps.size}", timestamps.size >= 20)
            assertEquals("输出时间戳必须严格单调（不能倒退）", timestamps.sorted(), timestamps)
            assertEquals("回放帧必须被丢弃，播放器不应看到重复画面", 0, replaySeen)
            assertTrue("时间轴必须跨越多个会话继续增长，实际末期 ${timestamps.last()}",
                timestamps.last() > timestamps.first())
        } finally {
            relay.close()
            server.stop(0)
        }
    }
}
