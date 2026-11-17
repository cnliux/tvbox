package com.tvbox.app.data.live

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 本机连续 FLV 中继（纯 Kotlin，不依赖 Go 侧代理）。
 *
 * 要解决的问题（均已实测）：
 *  - 虎牙 HTTP-FLV 单次会话在 **120~137 秒**被 CDN 正常关闭；
 *  - 斗鱼地址带 `expire` 租约，到期后 CDN 主动断流；
 *  - 重连后 CDN 常先回吐数秒旧缓冲，直接拼接会出现「画面回放」、
 *    时间戳归零会让播放器跳回开头。
 *
 * 做法：播放器只连 `127.0.0.1:<临时端口>/<随机路径>` 这一条**永不结束**的本地 FLV，
 * 上游断开由中继换新签名续拉，[FlvSplicer] 负责去回放与时间轴平移。
 * 播放器侧不会再收到 ENDED，因此不会再触发「重新 prepare」造成的可见黑屏。
 *
 * 相对 Go 版代理的取舍：
 *  - 保留「计划内预建第二条连接」：虎牙的会话上限是确定性周期，提前建连可以消掉
 *    EOF 之后的解析 + 握手空窗；
 *  - 不做「旧流追上关键帧再切换」的精确对齐：预建连接就绪即在本段结束时接手，
 *    重叠的旧帧由帧内容指纹去重消化，少一条状态机、行为更可预测；
 *  - 不做 pacer 匀速写出：节奏交给播放器自身缓冲策略，避免代理与播放器双重限速。
 */
class LiveContinuityRelay private constructor(
    private val site: String,
    private val roomId: String,
    private val initialLine: LiveLine,
    private val renew: (Int) -> LiveLine?,
) : LiveProxyHandle {

    companion object {
        private const val LOG_TAG = "TVBoxLiveRelay"

        /** 虎牙实测会话 120~137s，取 125s 作为标称值；其它平台不预设周期。 */
        private const val HUYA_NOMINAL_SESSION_MS = 125_000L

        /** 预建提前量：覆盖一次平台解析 + TLS 握手 + 读到首个关键帧。 */
        private const val PLANNED_LEAD_MS = 12_000L

        /** 上游空闲读超时；必须小于播放器侧硬看门狗（12s），代理才能先自愈。 */
        private const val IDLE_READ_TIMEOUT_MS = 8_000

        /** 下游连续多久没有写出标签就判定冻结（与具体故障类型无关的兜底）。 */
        private const val PROGRESS_STALL_MS = 8_000L

        /**
         * 可复用地址的平台：同一地址最多连续快速续拉几次，之后强制重解析拿新签名。
         *
         * 虎牙**不适用**，见 [reusableAddress]。
         */
        private const val MAX_FAST_RECONNECT = 3

        /** 小于该时长的上游会话视为异常，需要退避并考虑换线。 */
        private const val SHORT_SESSION_MS = 5_000L

        private const val CONNECT_TIMEOUT_MS = 15_000

        /** FLV 文件头 + PreviousTagSize0；标志位 0x05 = 有音有视频。 */
        private val FLV_HEADER: ByteArray = byteArrayOf(
            0x46, 0x4C, 0x56, 0x01, 0x05, 0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x00,
        )

        /** 启动中继；端口绑定失败返回 null，调用方回退为直连或旧代理。 */
        fun start(
            site: String,
            roomId: String,
            line: LiveLine,
            renew: (Int) -> LiveLine?,
        ): LiveContinuityRelay? = runCatching {
            LiveContinuityRelay(site, roomId, line, renew).apply { launch() }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "中继启动失败：${error.message}")
            null
        }
    }

    // 必须显式绑定 IPv4 回环：getLoopbackAddress() 在部分 Android 实现上返回
    // IPv6 的 ::1，而播放器连接的是 127.0.0.1，两者不互通 → 连接被拒绝，
    // 表现为 ERROR_CODE_IO_NETWORK_CONNECTION_FAILED（实机已复现）。
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val path = "/live-" + java.util.UUID.randomUUID().toString().replace("-", "") + ".flv"
    private val localUrl = "http://127.0.0.1:${server.localPort}$path"

    private val reconnects = AtomicInteger(0)
    private val plannedHandovers = AtomicInteger(0)
    private val idleTimeouts = AtomicInteger(0)
    private val malformedTags = AtomicInteger(0)
    private val shortSessions = AtomicInteger(0)
    private val prepared = AtomicReference<Segment?>(null)

    @Volatile private var closed = false
    @Volatile private var client: Socket? = null
    @Volatile private var currentConnection: HttpURLConnection? = null
    @Volatile private var lastWriteAt = System.currentTimeMillis()
    @Volatile private var lastError: String = ""

    private fun launch() {
        Thread({ acceptLoop() }, "live-relay-accept").apply { isDaemon = true }.start()
    }

    override fun localUrl(): String = localUrl

    override fun close() {
        closed = true
        prepared.getAndSet(null)?.close()
        runCatching { client?.close() }
        runCatching { currentConnection?.disconnect() }
        runCatching { server.close() }
    }

    /** 一条紧凑的统计串，写入 logcat 与播放页调试面板。 */
    override fun stats(): String = buildString {
        append('{')
        append("\"reconnects\":").append(reconnects.get()).append(',')
        append("\"plannedHandovers\":").append(plannedHandovers.get()).append(',')
        append("\"idleTimeouts\":").append(idleTimeouts.get()).append(',')
        append("\"malformedTags\":").append(malformedTags.get()).append(',')
        append("\"shortSessions\":").append(shortSessions.get()).append(',')
        append("\"idleMs\":").append(System.currentTimeMillis() - lastWriteAt).append(',')
        append("\"lastError\":\"").append(lastError.replace("\"", "'")).append('"')
        append('}')
    }

    // ── 客户端接入 ───────────────────────────────────────────────

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (error: IOException) {
                return
            }
            val requestPath = runCatching { socket.readRequestPath() }.getOrNull()
            if (requestPath != path) {
                runCatching { socket.close() }
                continue
            }
            runCatching { socket.tcpNoDelay = true }
            // 播放器只会有一条本地流；重连时替换旧连接。
            val previous = client
            client = socket
            runCatching { previous?.close() }
            lastWriteAt = System.currentTimeMillis()
            Thread({ streamLoop(socket) }, "live-relay-stream").apply { isDaemon = true }.start()
        }
    }

    /** 读 HTTP 请求行与请求头，返回请求路径。 */
    private fun Socket.readRequestPath(): String? {
        val reader = getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return null
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        return requestLine.split(' ').getOrNull(1)
    }

    // ── 转发主循环 ───────────────────────────────────────────────

    private fun streamLoop(socket: Socket) {
        val splicer = FlvSplicer()
        var line = initialLine
        var fastStreak = 0
        var shortStreak = 0
        // HTTP 响应头必须**直接**写到 socket：它不属于 chunked 正文。
        // 若把响应头交给 ChunkedOutputStream，头部会被当成一个 chunk 加长度前缀，
        // 播放器解析首行失败并抛 "Invalid Http response"（实测），表现为完全无法起播。
        val rawOutput = socket.getOutputStream()

        try {
            writeHttpHead(rawOutput)
            val output = ChunkedOutputStream(rawOutput)
            output.write(FLV_HEADER)
            output.flush()
            lastWriteAt = System.currentTimeMillis()
            startWatchdog()

            while (!closed) {
                val handover = prepared.getAndSet(null)
                val segment = handover ?: openSegment(line)
                if (segment == null) {
                    // 连不上：退避后重新解析，避免在失效地址上空转。
                    Thread.sleep(600)
                    renew(0)?.let { line = it }
                    continue
                }
                if (handover != null) plannedHandovers.incrementAndGet()

                val startedAt = System.currentTimeMillis()
                schedulePrepareForHuya(line, startedAt)
                val result = forward(segment, splicer, output)

                if (closed || result == ForwardResult.ClientGone) return

                val sessionMs = System.currentTimeMillis() - startedAt
                if (sessionMs < SHORT_SESSION_MS) {
                    shortSessions.incrementAndGet()
                    shortStreak++
                } else {
                    shortStreak = 0
                }
                reconnects.incrementAndGet()

                // 预备连接已就绪：直接接上，不走解析。
                if (prepared.get() != null) {
                    fastStreak = 0
                    continue
                }

                if (shortStreak >= 2) {
                    // 连续秒级会话说明这条线不可用：退避后重新解析并换线。
                    Thread.sleep(minOf(500L * shortStreak, 4_000L))
                    fastStreak = 0
                    renew(0)?.let { line = it }
                    continue
                }

                when (result) {
                    // 正常 EOF / 中途被掐断。
                    ForwardResult.Eof, ForwardResult.Malformed -> {
                        // 虎牙地址一次性（实测第二次同 URL 立刻 403），
                        // 必须每次重签；可复用平台才允许有限次同地址续拉。
                        if (!reusableAddress() || fastStreak >= MAX_FAST_RECONNECT) {
                            fastStreak = 0
                            renew(0)?.let { line = it }
                        } else {
                            fastStreak++
                        }
                    }
                    // 403/401 或空闲超时：签名或连接已失效，必须重新解析。
                    ForwardResult.Forbidden, ForwardResult.Idle, ForwardResult.Failed -> {
                        fastStreak = 0
                        renew(0)?.let { line = it }
                    }
                    ForwardResult.ClientGone -> return
                }
            }
        } catch (error: Throwable) {
            if (!closed) {
                lastError = error.message.orEmpty()
                Log.w(LOG_TAG, "转发结束：${error.message}")
            }
        } finally {
            // 关闭 socket 会一并关闭底层流；这里不再引用 output（可能在 try 内提前返回）。
            runCatching { socket.close() }
        }
    }

    private fun writeHttpHead(output: OutputStream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: video/x-flv\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** 冻结看门狗：8s 没有任何标签写出就断开当前上游，交给软重连。 */
    private fun startWatchdog() {
        Thread({
            while (!closed) {
                Thread.sleep(2_000)
                if (System.currentTimeMillis() - lastWriteAt > PROGRESS_STALL_MS) {
                    lastError = "progressStall"
                    runCatching { currentConnection?.disconnect() }
                }
            }
        }, "live-relay-watchdog").apply { isDaemon = true }.start()
    }

    // ── 单段上游 ─────────────────────────────────────────────────

    private enum class ForwardResult { Eof, Malformed, Forbidden, Idle, Failed, ClientGone }

    /** 一条已连通并读完 FLV 头的上游会话。 */
    private inner class Segment(
        val connection: HttpURLConnection,
        val reader: FlvTagReader,
        val line: LiveLine,
    ) {
        fun close() {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 建立一条上游连接。[track] 为 true 时登记为「当前在转发的连接」，
     * 供冻结看门狗断开；预建连接不能登记，否则看门狗会误断正在用的那一条。
     */
    private fun openSegment(line: LiveLine, track: Boolean = true): Segment? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(line.url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = IDLE_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "*/*")
            for ((key, value) in line.headers) {
                // Host/Content-Length 由连接自己管理，透传会破坏请求。
                if (key.equals("host", true) || key.equals("content-length", true)) continue
                connection.setRequestProperty(key, value)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                lastError = "HTTP $code"
                connection.disconnect()
                return null
            }
            if (track) currentConnection = connection
            val reader = FlvTagReader(connection.inputStream)
            if (!reader.readFileHeader()) {
                lastError = "上游不是 FLV"
                connection.disconnect()
                return null
            }
            Segment(connection, reader, line)
        } catch (error: Throwable) {
            lastError = error.message.orEmpty()
            runCatching { connection?.disconnect() }
            null
        }
    }

    private fun forward(segment: Segment, splicer: FlvSplicer, output: ChunkedOutputStream): ForwardResult {
        splicer.startSegment()
        try {
            while (!closed) {
                val tag = try {
                    segment.reader.nextTag()
                } catch (error: MalformedTagException) {
                    // 伪长度无法再同步流边界，只能丢弃本段重连。
                    malformedTags.incrementAndGet()
                    lastError = "malformed tag size=${error.size}"
                    return ForwardResult.Malformed
                } catch (error: SocketTimeoutException) {
                    idleTimeouts.incrementAndGet()
                    lastError = "upstream idle"
                    return ForwardResult.Idle
                } catch (error: IOException) {
                    // 会话中途被 CDN 掐断（含读到一半的标签）按正常结束处理。
                    return ForwardResult.Eof
                } ?: return ForwardResult.Eof

                for (out in splicer.accept(tag)) {
                    try {
                        output.write(out.bytes)
                        output.flush()
                        lastWriteAt = System.currentTimeMillis()
                    } catch (error: IOException) {
                        return ForwardResult.ClientGone
                    }
                }
            }
            return ForwardResult.Eof
        } finally {
            segment.close()
            currentConnection = null
        }
    }

    /**
     * 旧地址是否还能直接再用一次。
     *
     * 虎牙返回 false：**实测确认**（房间 11336726，AL 线路）anti-code URL 是一次性的——
     * 第一次请求拉到 100667ms/9.2MB 后正常 EOF，同一 URL 第二次请求 135ms 就返回 403。
     * 复用旧地址只会制造每秒数次的 403 重连风暴：10 分钟长跑中虎牙 3 个房间分别产生
     * 420/278/278 个会话，中位会话仅 152~168ms（正常应为 ~120s）。
     * 因此虎牙每次都必须重新解析拿新签名；斗鱼/其它平台暂按可复用处理。
     */
    private fun reusableAddress(): Boolean = site.lowercase() != "huya"

    /**
     * 计划内预建只对虎牙启用（会话上限是确定性周期）。
     * 到点后在后台建第二条连接、读到首个关键帧即视为就绪，等本段结束后接手。
     */
    private fun schedulePrepareForHuya(line: LiveLine, segmentStartedAt: Long) {
        if (site.lowercase() != "huya") return
        if (prepared.get() != null) return
        val delay = HUYA_NOMINAL_SESSION_MS - PLANNED_LEAD_MS - (System.currentTimeMillis() - segmentStartedAt)
        if (delay <= 0) return
        Thread({
            try {
                Thread.sleep(delay)
                if (closed || prepared.get() != null) return@Thread
                // 关键：预建连接必须用**新签名**。
                // 虎牙 anti-code URL 实测一次性（同 URL 第二次 135ms 即 403），
                // 拿旧 [line] 去建第二条连接必然立刻失败，预建等于没做。
                val renewed = renew(0)
                val target = renewed ?: line
                val next = openSegment(target, track = false) ?: return@Thread
                var guard = 0
                while (guard++ < 2_000) {
                    val tag = next.reader.nextTag() ?: break
                    if (FlvTags.isKeyframe(tag)) {
                        if (!prepared.compareAndSet(null, next)) next.close()
                        return@Thread
                    }
                }
                next.close()
            } catch (error: Throwable) {
                lastError = error.message.orEmpty()
            }
        }, "live-relay-prepare").apply { isDaemon = true }.start()
    }

    /** chunked 输出：把任意长度的写入包装成一个不会结束的 HTTP 响应体。 */
    private class ChunkedOutputStream(private val socketOut: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            socketOut.write((Integer.toHexString(len) + "\r\n").toByteArray(Charsets.ISO_8859_1))
            socketOut.write(b, off, len)
            socketOut.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }

        override fun flush() = socketOut.flush()

        override fun close() {
            runCatching {
                socketOut.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                socketOut.flush()
            }
            runCatching { socketOut.close() }
        }
    }
}
