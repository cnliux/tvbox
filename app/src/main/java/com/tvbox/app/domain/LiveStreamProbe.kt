package com.tvbox.app.domain

import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.util.logSafeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 无扩展名直播地址的内容类型探测。
 *
 * IPTV 源里大量地址形如 `https://cdn.test/play?id=cctv1`，URL 上没有 `.m3u8/.flv` 线索。
 * media3 的 `DefaultMediaSourceFactory` 只按 URI 后缀判断媒体类型，判不出来就退化成
 * progressive 数据源，再用抽取器嗅探——文本形式的 m3u8 不属于任何抽取器，于是抛出
 * `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`（"None of the available extractors…"），
 * 表现为"频道打不开/黑屏"，而这条线路本身是好的。
 *
 * 这里在取流前发一个极小的探测请求（带 Range，只读头部若干字节），按响应头与正文
 * 特征还原真实类型，交给 ExoPlayer 正确的 MediaSource（HLS / FLV / TS）。
 */
private const val LIVE_PROBE_LOG_TAG = "TVBoxLiveSource"
private const val LIVE_PROBE_READ_BYTES = 4096

/** MIME 缺失或无法识别，交给播放器自行判断（保持原有行为）。 */
const val LIVE_MIME_UNKNOWN = ""

const val LIVE_MIME_M3U8 = "application/x-mpegURL"
const val LIVE_MIME_FLV = "video/x-flv"
const val LIVE_MIME_MP2T = "video/mp2t"

// URL → MIME（仅缓存探测成功的判定结果），避免重连/切回时重复探测。
private val probeCache = ConcurrentHashMap<String, String>()

/**
 * 返回该地址真实的 MIME（[LIVE_MIME_M3U8] / [LIVE_MIME_FLV] / [LIVE_MIME_MP2T]），
 * 无法判定或请求失败时返回 [LIVE_MIME_UNKNOWN]，调用方按原逻辑取流即可。
 */
suspend fun probeLiveLineMimeType(
    url: String,
    headers: Map<String, String> = emptyMap(),
): String {
    if (!url.startsWith("http", ignoreCase = true)) return LIVE_MIME_UNKNOWN
    // 带明确扩展名的地址交给 media3 自行判断，不浪费一次往返。
    if (url.substringBefore('?').substringBefore('#').contains('.')) {
        val hinted = url.substringBefore('?').substringAfterLast('.', "")
        if (hinted.isNotBlank() && LIVE_PROBE_KNOWN_EXTENSIONS.any { hinted.equals(it, ignoreCase = true) }) {
            return LIVE_MIME_UNKNOWN
        }
    }
    probeCache[url]?.let { return it }
    val probed = withContext(Dispatchers.IO) { runCatching { probeBlocking(url, headers) }.getOrDefault(LIVE_MIME_UNKNOWN) }
    // 失败/无法判定不入缓存：避免一次网络抖动让该地址永久失去探测与黑屏修复机会。
    if (probed != LIVE_MIME_UNKNOWN) probeCache[url] = probed
    android.util.Log.i(
        LIVE_PROBE_LOG_TAG,
        "【类型探测】url=${logSafeUrl(url)} mime=${probed.ifBlank { "未知（沿用播放器判断）" }}",
    )
    return probed
}

private suspend fun probeBlocking(url: String, headers: Map<String, String>): String {
    val builder = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-${LIVE_PROBE_READ_BYTES - 1}")
    headers.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank() && value.none { ch -> ch == '\r' || ch == '\n' }) {
            builder.header(key, value)
        }
    }
    HttpClients.probe.executeCancellable(builder.build()).use { response ->
        if (!response.isSuccessful) return LIVE_MIME_UNKNOWN
        val contentType = response.header("Content-Type")
        val bodyRef = response.body
        // 先按响应头判断：CDN 通常会把 m3u8 标成 audio/x-mpegurl 或 application/vnd.apple.mpegurl。
        contentType?.let { fromHeader -> mimeFromContentType(fromHeader)?.let { return it } }
        // 响应头不可信（很多源统一返回 text/plain）时读正文特征。
        val buffer = ByteArray(LIVE_PROBE_READ_BYTES)
        val stream = bodyRef.byteStream()
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return mimeFromBodyHead(buffer, read)
    }
}

/** 仅按 Content-Type 判定；不确定返回 null。 */
internal fun mimeFromContentType(contentType: String): String? {
    val value = contentType.lowercase()
    return when {
        value.contains("mpegurl") || value.contains("vnd.apple.mpegurl") -> LIVE_MIME_M3U8
        value.contains("x-flv") -> LIVE_MIME_FLV
        value.contains("mp2t") || value.contains("mp2ts") -> LIVE_MIME_MP2T
        else -> null
    }
}

/** 按正文头部特征判定：文本 m3u8 / FLV 签名 / TS 同步字节 0x47。 */
internal fun mimeFromBodyHead(buffer: ByteArray, length: Int): String {
    if (length <= 0) return LIVE_MIME_UNKNOWN
    val text = String(buffer, 0, minOf(length, 64), Charsets.ISO_8859_1)
    val trimmed = text.trimStart('﻿', ' ', '\t', '\r', '\n')
    if (trimmed.startsWith("#EXTM3U")) return LIVE_MIME_M3U8
    if (length >= 3 && buffer[0] == 'F'.code.toByte() && buffer[1] == 'L'.code.toByte() && buffer[2] == 'V'.code.toByte()) {
        return LIVE_MIME_FLV
    }
    // MPEG-TS：每 188 字节一个包，首字节为同步字 0x47。
    if (buffer[0] == 0x47.toByte() && (length <= 188 || buffer[188] == 0x47.toByte())) {
        return LIVE_MIME_MP2T
    }
    return LIVE_MIME_UNKNOWN
}

private val LIVE_PROBE_KNOWN_EXTENSIONS = listOf(
    "m3u8", "m3u", "flv", "ts", "mp4", "mkv", "mpd", "m4s", "mov", "mpeg", "mpg",
)

/**
 * 测量一条直播线路的首包延迟（毫秒）。
 *
 * 复用 [HttpClients.probe] 的短超时与 Range 头策略：只读首个分片，避免为了测速把整路
 * 直播流拉起来。返回 null 表示不可达/超时，调用方应展示"超时"而不是 0ms。
 */
suspend fun measureLiveLineLatencyMs(
    url: String,
    headers: Map<String, String> = emptyMap(),
): Long? {
    if (!url.startsWith("http", ignoreCase = true)) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${LIVE_PROBE_READ_BYTES - 1}")
            headers.forEach { (key, value) ->
                val clean = key.isNotBlank() && value.isNotBlank() &&
                    value.none { ch -> ch == '\r' || ch == '\n' }
                if (clean) builder.header(key, value)
            }
            val startedAt = System.currentTimeMillis()
            HttpClients.probe.executeCancellable(builder.build()).use { response ->
                if (!response.isSuccessful) return@use null
                // 读到首字节即可结束：这才是播放器真正开始出画的时间点。
                response.body.byteStream().use { stream ->
                    val first = stream.read()
                    if (first < 0) null else System.currentTimeMillis() - startedAt
                }
            }
        }.getOrNull()
    }
}
