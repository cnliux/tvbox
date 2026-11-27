package com.tvbox.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * 直播线路响应速度测量。
 *
 * 目的：线路列表要显示「AL · 220ms」这样的延迟副标题，让用户在切换前就能判断
 * 哪条线路快，而不是切过去卡了才知道。
 *
 * 测量口径刻意选「**首字节时间**（TTFB）」而不是下载完整片段：
 *  - 直播流是无限的，不可能下载完；
 *  - 只读一小段就断开，对 CDN 友好、对用户流量几乎无成本；
 *  - TTFB 与「起播要等多久」强相关，正是用户感知的「快慢」。
 *
 * 支持 Range 的线路发 `bytes=0-2047`；不支持（部分 CDN 返回 200 全量）时
 * 读到阈值就主动断开，绝不把整条流读下来。
 */
object LiveLineLatency {

    /** 单条线路的测速上限；超过即视为慢线并记为该值（避免个别线路拖慢整个列表）。 */
    private const val PROBE_TIMEOUT_MS = 3_000

    /** 读到这么多字节就足够得到 TTFB，立即断开。 */
    private const val PROBE_BYTES = 2_048

    /** 并发上限：线路通常 2~5 条，全部并发也不会打爆网络。 */
    private const val MAX_PARALLEL = 6

    /**
     * 并发测量多条线路，返回与输入**同序**的延迟（毫秒）；失败为 -1。
     *
     * 同序很重要：调用方按「平台优先级」排列候选，测量结果不能打乱这个顺序
     * （虎牙 AL/TX/HS 的优先级来自平台，不是速度决定的）。
     *
     * [onResult] 可选：每条线路测完立即回调 `(索引, 延迟毫秒)`，供 UI 逐条实时刷新，
     * 而不必等全部线路测完才出结果。回调在 IO 上下文触发，调用方需自行切回主线程。
     */
    suspend fun measure(
        lines: List<Pair<String, Map<String, String>>>,
        onResult: ((index: Int, latencyMs: Int) -> Unit)? = null,
    ): List<Int> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        val results = arrayOfNulls<Int>(lines.size)
        val semaphore = Semaphore(MAX_PARALLEL)
        coroutineScope {
            lines.mapIndexed { index, (url, headers) ->
                async {
                    // 信号量保证原子并发上限；检查+自增不再是分离操作。
                    semaphore.withPermit {
                        val latency = probe(url, headers)
                        results[index] = latency
                        onResult?.invoke(index, latency)
                    }
                }
            }.awaitAll()
        }
        results.map { it ?: -1 }
    }

    /** 单条线路的首字节毫秒数；失败返回 -1。 */
    private fun probe(url: String, headers: Map<String, String>): Int {
        var connection: HttpURLConnection? = null
        val startedAt = System.nanoTime()
        return try {
            // R3：用户导入的源可能含非法/非 http(s) URL；openConnection 也可能返回
            // 非 HttpURLConnection。两者都按「测速失败」处理，绝不让异常冒泡到调用方。
            val opened = try {
                URL(url).openConnection()
            } catch (_: Exception) {
                return -1
            }
            connection = opened as? HttpURLConnection ?: return -1
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Range", "bytes=0-$PROBE_BYTES")
            connection.setRequestProperty("Accept", "*/*")
            for ((key, value) in headers) {
                if (key.equals("host", true) || key.equals("range", true)) continue
                connection.setRequestProperty(key, value)
            }
            val code = connection.responseCode
            if (code !in 200..299) return -1
            val read = connection.inputStream.use { input ->
                val buffer = ByteArray(1024)
                // 阻塞到第一个字节到达：这就是 TTFB。
                input.read(buffer)
            }
            val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
            if (read <= 0) return -1
            max(elapsed, 1)
        } catch (error: Throwable) {
            -1
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
