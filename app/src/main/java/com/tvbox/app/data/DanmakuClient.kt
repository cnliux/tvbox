package com.tvbox.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 一条直播弹幕。[color] 为 ARGB 颜色，0 表示默认白色。 */
data class DanmakuMessage(
    val userName: String,
    val text: String,
    val color: Int = 0,
)

/** 平台直播弹幕客户端：接入平台官方弹幕长连接，连接断开后自动重连。 */
interface DanmakuClient {
    val messages: SharedFlow<DanmakuMessage>
    fun start()
    fun close()
}

/** 按平台创建弹幕客户端；暂不支持的平台返回 null。 */
object DanmakuClients {
    private const val TAG = "TVBoxDanmaku"

    fun create(site: String, roomId: String): DanmakuClient? = runCatching {
        // 应用内的房间 id 形如 `douyu:9990337`，弹幕服务器只认纯房间号。
        val rid = roomId.substringAfterLast(':').ifBlank { roomId }
        when (site.lowercase()) {
            "douyu" -> DouyuDanmakuClient(rid)
            "huya" -> HuyaDanmakuClient(rid)
            else -> null
        }
    }.getOrElse { error ->
        Log.w(TAG, "创建弹幕客户端失败 site=$site room=$roomId: ${error.message}")
        null
    }
}

/**
 * 弹幕 WebSocket 基类：负责连接、心跳、断线重连与消息分发。
 *
 * 平台弹幕服务器都会在空闲时主动断开，因此这里统一做「失败/关闭 → 3s 后重连」，
 * 并把心跳交给平台各自实现（斗鱼 45s 文本心跳、虎牙 60s 固定二进制心跳）。
 */
internal abstract class BaseDanmakuClient(
    private val url: String,
    private val heartbeatIntervalMs: Long,
) : DanmakuClient {

    private val logTag: String = javaClass.simpleName
    private val _messages = MutableSharedFlow<DanmakuMessage>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: SharedFlow<DanmakuMessage> = _messages

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        // 弹幕流是长连接，读超时交给平台心跳保活，不用 HTTP 读超时。
        // 注意不能开 OkHttp 的 pingInterval：斗鱼会把它判成「Control frames must be final」并断开。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var stopped = false
    @Volatile
    private var connected = false
    @Volatile
    private var emittedCount = 0

    protected abstract fun onConnected(ws: WebSocket)
    protected abstract fun onPayload(bytes: ByteArray)
    protected open fun onPayload(text: String) = Unit

    protected fun log(message: String) = Log.i(logTag, message)

    protected fun logWarn(message: String) = Log.w(logTag, message)

    override fun start() {
        if (stopped) {
            stopped = false
        } else if (connected || socket != null || reconnectJob?.isActive == true) {
            return
        }
        connect()
    }

    override fun close() {
        stopped = true
        connected = false
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        runCatching { socket?.close(1000, null) }
        socket = null
        scope.cancel()
    }

    protected fun emit(message: DanmakuMessage) {
        if (message.text.isBlank()) return
        // 少量抽样打日志，便于现场确认弹幕确实在流动（不刷屏）。
        emittedCount += 1
        if (emittedCount % 20 == 1) {
            Log.i(logTag, "【弹幕#$emittedCount】${message.userName}: ${message.text}")
        }
        _messages.tryEmit(message)
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        Log.i(logTag, "【弹幕连接】$url")
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) {
                    webSocket.close(1000, null)
                    return
                }
                connected = true
                Log.i(logTag, "【弹幕已连接】$url")
                runCatching { onConnected(webSocket) }
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { onPayload(bytes.toByteArray()) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { onPayload(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                if (stopped) return
                Log.w(logTag, "【弹幕断开】${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                if (stopped) return
                Log.i(logTag, "【弹幕关闭】code=$code reason=$reason")
                scheduleReconnect()
            }
        })
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!stopped && connected) {
                delay(heartbeatIntervalMs)
                if (!sendHeartbeat(ws)) break
            }
        }
    }

    /** 返回 false 表示连接已不可用，交由 onFailure/onClosed 触发重连。 */
    protected abstract fun sendHeartbeat(ws: WebSocket): Boolean

    private fun scheduleReconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        socket = null
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (stopped) return@launch
            log("【弹幕重连】$url")
            connect()
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val RECONNECT_DELAY_MS = 3_000L
    }
}

/**
 * 斗鱼弹幕：`wss://danmuproxy.douyu.com:8506` + STT 文本协议。
 *
 * 帧格式（小端）：len(4) len(4) type=689(2) encrypt(1) reserved(1) body(n) 0x00(1)，
 * 登录后发 `loginreq` + `joingroup(gid=-9999)`，之后每 45s 发一次 `mrkl` 心跳。
 */
internal class DouyuDanmakuClient(private val roomId: String) :
    BaseDanmakuClient("wss://danmuproxy.douyu.com:8506", 45_000L) {

    private var framesSeen = 0
    private var chatSeen = 0

    override fun onConnected(ws: WebSocket) {
        log("【斗鱼进房】room=$roomId")
        send(ws, "type@=loginreq/roomid@=$roomId/")
        send(ws, "type@=joingroup/rid@=$roomId/gid@=-9999/")
    }

    override fun sendHeartbeat(ws: WebSocket): Boolean = send(ws, "type@=mrkl/")

    private fun send(ws: WebSocket, body: String): Boolean {
        val payload = body.toByteArray(Charsets.UTF_8)
        // 斗鱼帧：len(4) len(4) type(2) encrypt(1) reserved(1) body(n) 0x00(1)。
        // 协议里的长度字段 = 帧总长 - 4（即 9 + body）；写成帧总长会被服务端静默丢弃。
        val total = 4 + 4 + 2 + 1 + 1 + payload.size + 1
        val lengthField = total - 4
        val frame = ByteArrayOutputStream(total)
        writeLe(frame, lengthField.toLong(), 4)
        writeLe(frame, lengthField.toLong(), 4)
        writeLe(frame, PACKET_TYPE_CLIENT, 2)
        frame.write(0) // encrypted
        frame.write(0) // reserved
        frame.write(payload)
        frame.write(0)
        return runCatching { ws.send(ByteString.of(*frame.toByteArray())) }.getOrDefault(false)
    }

    override fun onPayload(bytes: ByteArray) {
        // 一个 WebSocket 帧里可能拼接多段消息，按长度字段逐段切分。
        var offset = 0
        while (offset + 12 <= bytes.size) {
            val length = readLe(bytes, offset, 4).toInt()
            if (length <= 9 || offset + length + 4 > bytes.size) break
            val bodyLength = length - 9
            val body = String(bytes, offset + 12, bodyLength, Charsets.UTF_8)
            parseBody(body)
            offset += length + 4
        }
    }

    private fun parseBody(body: String) {
        // 一帧里偶尔拼多条消息，服务端用 NUL 分隔；正常情况下就是一整条。
        val segments = body.split('\u0000').filter { it.isNotBlank() }
        segments.forEach { segment ->
            val fields = parseStt(segment)
            val type = fields["type"]
            // 前几帧打印 type，便于现场确认登录/进房是否被服务端接受。
            if (framesSeen < 6) {
                framesSeen += 1
                log("【斗鱼帧#$framesSeen】type=$type code=${fields["code"]} room=${fields["rid"]}")
            }
            if (type != "chatmsg") return@forEach
            // 注意：不要用 dms（弹幕时间戳）判空来过滤——现网 chatmsg 并不带 dms，
            // 早前按 dms 过滤会把所有弹幕全部丢弃，表现为「弹幕永远不显示」。
            val text = fields["txt"].orEmpty().trim()
            if (text.isEmpty()) return@forEach
            if (chatSeen < 5) {
                chatSeen += 1
                log("【斗鱼弹幕】${fields["nn"]}: $text")
            }
            emit(
                DanmakuMessage(
                    userName = fields["nn"].orEmpty().ifBlank { "斗鱼用户" },
                    text = text,
                    color = douyuColor(fields["col"]?.toIntOrNull() ?: 0),
                ),
            )
        }
    }

    private fun parseStt(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        raw.split('/').forEach { field ->
            val index = field.indexOf("@=")
            if (index > 0) {
                val key = field.substring(0, index)
                // "@S" → "/"、"@A" → "@"
                map[key] = field.substring(index + 2).replace("@S", "/").replace("@A", "@")
            }
        }
        return map
    }

    private fun douyuColor(type: Int): Int = when (type) {
        1 -> 0xFFFF0000.toInt()
        2 -> 0xFF1E87F0.toInt()
        3 -> 0xFF7AC84B.toInt()
        4 -> 0xFFFF7F00.toInt()
        5 -> 0xFF9B39F4.toInt()
        6 -> 0xFFFF69B4.toInt()
        else -> 0
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in 0 until size) out.write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun readLe(bytes: ByteArray, offset: Int, size: Int): Long {
        var result = 0L
        for (i in 0 until size) {
            result = result or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return result
    }

    companion object {
        private const val PACKET_TYPE_CLIENT = 689L
    }
}

/**
 * 虎牙弹幕：`wss://wsapi.huya.com` + Tars 命令协议（现网协议，旧 cdnws 端点已不推弹幕）。
 *
 * 连接后发送 command 16（RegisterGroupReq）注册 `live:{uid}` 与 `chat:{uid}`
 * 两个分组，之后每 60s 发一次 command 20（HeartBeatReq）保活；服务端以
 * command 7 单条推送 / command 22 批量推送下发，其中 uri=1400 为聊天弹幕。
 *
 * uid 必须取主播的 `lUid`（不是 `lYyid`）：用 lYyid 注册只会收到直播间通知，
 * 收不到任何聊天消息——这是实测确认过的坑。
 */
internal class HuyaDanmakuClient(private val roomId: String) :
    BaseDanmakuClient("wss://wsapi.huya.com", 60_000L) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override fun onConnected(ws: WebSocket) {
        // 页面请求是阻塞的，放到 IO 线程，避免卡住 WebSocket 回调线程；连上先发一次心跳。
        scope.launch {
            ws.send(ByteString.of(*buildHeartbeatPacket()))
            val uid = resolveStreamerUid()
            if (uid == null) {
                logWarn("【虎牙弹幕】主播 uid 解析失败 room=$roomId，跳过注册")
                return@launch
            }
            runCatching { ws.send(ByteString.of(*buildRegisterPacket(uid))) }
            log("【虎牙弹幕注册】room=$roomId uid=$uid groups=live:$uid,chat:$uid")
        }
    }

    override fun sendHeartbeat(ws: WebSocket): Boolean =
        runCatching { ws.send(ByteString.of(*buildHeartbeatPacket())) }.getOrDefault(false)

    override fun onPayload(bytes: ByteArray) {
        val top = runCatching { TarsReader(bytes).readStruct() }.getOrNull() ?: return
        val payload = top[1] as? ByteArray ?: return
        when ((top[0] as? Long)?.toInt()) {
            // 单条推送：tag1 uri、tag2 消息体。
            COMMAND_PUSH -> {
                val push = runCatching { TarsReader(payload).readStruct() }.getOrNull() ?: return
                handlePush((push[1] as? Long)?.toInt() ?: 0, push[2] as? ByteArray)
            }
            // 批量推送：tag1 为条目列表，每条含 tag0 uri、tag1 消息体。
            COMMAND_BATCH -> {
                val batch = runCatching { TarsReader(payload).readStruct() }.getOrNull() ?: return
                val items = batch[1] as? List<*> ?: return
                items.forEach { item ->
                    val entry = item as? Map<*, *> ?: return@forEach
                    handlePush(
                        (entry[0] as? Long)?.toInt() ?: 0,
                        entry[1] as? ByteArray,
                    )
                }
            }
            else -> Unit
        }
    }

    private fun handlePush(uri: Int, body: ByteArray?) {
        when (uri) {
            URI_CHAT -> parseChat(body)
            URI_POPULARITY -> Unit // 人气值暂不展示，只保留弹幕
        }
    }

    private fun parseChat(data: ByteArray?) {
        if (data == null) return
        val msg = runCatching { TarsReader(data).readStruct() }.getOrNull() ?: return
        val content = (msg[3] as? String)?.trim().orEmpty()
        if (content.isEmpty()) return
        val sender = msg[0] as? Map<*, *>
        val nick = (sender?.get(2) as? String).orEmpty().ifBlank { "虎牙用户" }
        val format = msg[6] as? Map<*, *>
        val color = (format?.get(0) as? Long)?.toInt() ?: 0
        emit(DanmakuMessage(userName = nick, text = content, color = if (color > 0) 0xFF000000.toInt() or color else 0))
    }

    /** 主播 uid（`lUid`）：注册分组用的是它，取值失败时退回频道号。 */
    private fun resolveStreamerUid(): Long? {
        val html = runCatching {
            val request = Request.Builder()
                .url("https://m.huya.com/$roomId")
                // 虎牙只在移动版（iPhone Safari）UA 下返回带房间号的 HNF_GLOBAL_INIT，
                // 桌面/安卓 UA 拿到的是不含频道号的 JS 外壳。
                .header("User-Agent", HUYA_PAGE_USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body.string()
            }
        }.getOrNull() ?: return null

        fun pick(pattern: String): Long =
            Regex(pattern).find(html)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

        return sequenceOf(
            pick("lUid\":([0-9]+)"),
            pick("lChannelId\":([0-9]+)"),
            pick("lSubChannelId\":([0-9]+)"),
        ).firstOrNull { it > 0 }
    }

    /** command 16：payload = { tag0: ["live:uid", "chat:uid"], tag1: "" }。 */
    private fun buildRegisterPacket(uid: Long): ByteArray {
        val payload = TarsWriter().apply {
            writeStringList(listOf("live:$uid", "chat:$uid"), 0)
            writeString("", 1)
        }.toByteArray()
        return TarsWriter().apply {
            writeInt(COMMAND_REGISTER.toLong(), 0)
            writeBytes(payload, 1)
        }.toByteArray()
    }

    /** command 20：payload 为空。 */
    private fun buildHeartbeatPacket(): ByteArray = TarsWriter().apply {
        writeInt(COMMAND_HEARTBEAT.toLong(), 0)
        writeBytes(ByteArray(0), 1)
    }.toByteArray()

    companion object {
        private const val COMMAND_REGISTER = 16
        private const val COMMAND_HEARTBEAT = 20
        private const val COMMAND_PUSH = 7
        private const val COMMAND_BATCH = 22
        private const val URI_CHAT = 1400
        private const val URI_POPULARITY = 8006

        private const val HUYA_PAGE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    }
}

/** 极简 Tars 写入器：只覆盖弹幕进房需要的 int/string/bytes。 */
internal class TarsWriter {
    private val out = ByteArrayOutputStream()

    fun writeInt(value: Long, tag: Int) {
        when {
            value == 0L -> head(TYPE_ZERO, tag)
            value in -128L..127L -> {
                head(TYPE_INT8, tag)
                out.write((value and 0xFF).toInt())
            }
            value in -32768L..32767L -> {
                head(TYPE_INT16, tag)
                writeBigEndian(value, 2)
            }
            value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> {
                head(TYPE_INT32, tag)
                writeBigEndian(value, 4)
            }
            else -> {
                head(TYPE_INT64, tag)
                writeBigEndian(value, 8)
            }
        }
    }

    fun writeString(value: String, tag: Int) {
        val data = value.toByteArray(Charsets.UTF_8)
        if (data.size <= 255) {
            head(TYPE_STRING1, tag)
            out.write(data.size)
        } else {
            head(TYPE_STRING4, tag)
            writeBigEndian(data.size.toLong(), 4)
        }
        out.write(data)
    }

    fun writeBytes(value: ByteArray, tag: Int) {
        head(TYPE_SIMPLE_LIST, tag)
        head(TYPE_INT8, 0) // 元素类型
        // 长度字段的 tag 固定为 0（对齐虎牙客户端的 Tars 实现），写成 1 服务端会静默丢弃。
        writeInt(value.size.toLong(), 0)
        out.write(value)
    }

    /** 字符串列表（tars LIST）：tag 后直接跟元素个数，各元素自带 tag 0 的头。 */
    fun writeStringList(values: List<String>, tag: Int) {
        head(TYPE_LIST, tag)
        writeInt(values.size.toLong(), 0)
        values.forEach { writeString(it, 0) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun head(type: Int, tag: Int) {
        if (tag < 15) {
            out.write((tag shl 4) or type)
        } else {
            out.write((15 shl 4) or type)
            out.write(tag)
        }
    }

    private fun writeBigEndian(value: Long, size: Int) {
        for (i in size - 1 downTo 0) {
            out.write(((value shr (8 * i)) and 0xFF).toInt())
        }
    }

    private companion object {
        const val TYPE_INT8 = 0
        const val TYPE_INT16 = 1
        const val TYPE_INT32 = 2
        const val TYPE_INT64 = 3
        const val TYPE_STRING1 = 6
        const val TYPE_STRING4 = 7
        const val TYPE_LIST = 9
        const val TYPE_STRUCT_END = 11
        const val TYPE_ZERO = 12
        const val TYPE_SIMPLE_LIST = 13
    }
}

/**
 * 极简 Tars 读取器：解析成 tag → 值（Long / String / ByteArray / 嵌套 Map）。
 * 结构体读到 STRUCT_END（type=11）结束，够用即可，不追求完整 Tars 语义。
 */
internal class TarsReader(private val buffer: ByteArray) {
    private var position = 0

    fun readStruct(): Map<Int, Any?> {
        val result = LinkedHashMap<Int, Any?>()
        while (position < buffer.size) {
            val head = readHead()
            if (head.type == TYPE_STRUCT_END) break
            result[head.tag] = readValue(head.type)
        }
        return result
    }

    private fun readHead(): Head {
        val first = buffer[position++].toInt() and 0xFF
        var tag = first shr 4
        val type = first and 0x0F
        if (tag == 15) {
            tag = buffer[position++].toInt() and 0xFF
        }
        return Head(tag, type)
    }

    private fun readValue(type: Int): Any? = when (type) {
        TYPE_INT8 -> readSigned(1)
        TYPE_INT16 -> readSigned(2)
        TYPE_INT32 -> readSigned(4)
        TYPE_INT64 -> readSigned(8)
        TYPE_FLOAT -> Float.fromBits(readSigned(4).toInt())
        TYPE_DOUBLE -> Double.fromBits(readSigned(8))
        TYPE_STRING1 -> readBytes(buffer[position++].toInt() and 0xFF).toString(Charsets.UTF_8)
        TYPE_STRING4 -> readBytes(readSigned(4).toInt()).toString(Charsets.UTF_8)
        TYPE_MAP -> {
            // 先读出 key/value 的类型声明，再按数量解析。
            val keyHead = readHead()
            val valueHead = readHead()
            val size = (readValue(readHead().type) as? Long)?.toInt() ?: 0
            val map = LinkedHashMap<Any?, Any?>()
            repeat(size.coerceAtLeast(0)) {
                val key = readValue(keyHead.type)
                map[key] = readValue(valueHead.type)
            }
            map
        }
        TYPE_LIST -> {
            // tars LIST：先元素个数（tag 0），随后每个元素自带一个头。
            val size = (readValue(readHead().type) as? Long)?.toInt() ?: 0
            val list = ArrayList<Any?>(size.coerceAtLeast(0))
            repeat(size.coerceAtLeast(0)) {
                list.add(readValue(readHead().type))
            }
            list
        }
        TYPE_STRUCT -> readStruct()
        TYPE_STRUCT_END -> null
        TYPE_ZERO -> 0L
        TYPE_SIMPLE_LIST -> {
            readHead() // 元素类型声明（通常为 int8）
            val size = (readValue(readHead().type) as? Long)?.toInt() ?: 0
            readBytes(size.coerceAtLeast(0))
        }
        else -> null
    }

    private fun readSigned(size: Int): Long {
        var value = 0L
        for (i in 0 until size) {
            value = (value shl 8) or (buffer[position++].toLong() and 0xFF)
        }
        // 大端补码 → 有符号数
        val shift = 64 - size * 8
        return (value shl shift) shr shift
    }

    private fun readBytes(size: Int): ByteArray {
        val end = (position + size).coerceAtMost(buffer.size)
        val data = buffer.copyOfRange(position, end)
        position = end
        return data
    }

    private data class Head(val tag: Int, val type: Int)

    private companion object {
        const val TYPE_INT8 = 0
        const val TYPE_INT16 = 1
        const val TYPE_INT32 = 2
        const val TYPE_INT64 = 3
        const val TYPE_FLOAT = 4
        const val TYPE_DOUBLE = 5
        const val TYPE_STRING1 = 6
        const val TYPE_STRING4 = 7
        const val TYPE_MAP = 8
        const val TYPE_LIST = 9
        const val TYPE_STRUCT = 10
        const val TYPE_STRUCT_END = 11
        const val TYPE_ZERO = 12
        const val TYPE_SIMPLE_LIST = 13
    }
}
