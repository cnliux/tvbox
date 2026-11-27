package com.tvbox.app.domain

import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.domain.DurationConfidence
import com.tvbox.app.domain.ResponseShape
import com.tvbox.app.domain.durationConfidence
import com.tvbox.app.util.logSafeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.nio.charset.Charset
import java.util.Collections
import java.util.LinkedHashMap

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
/** T1-04：GET Range 兜底探活的最大字节数（用于获取 Content-Length / Content-Range）。 */
private const val LIVE_PROBE_FALLBACK_RANGE_END = 65535L

/** MIME 缺失或无法识别，交给播放器自行判断（保持原有行为）。 */
const val LIVE_MIME_UNKNOWN = ""

const val LIVE_MIME_M3U8 = "application/x-mpegURL"
const val LIVE_MIME_FLV = "video/x-flv"
const val LIVE_MIME_MP2T = "video/mp2t"

// (URL + 规范化 headers) → 探活结果（含插入时间戳）；TTL 内有效，超过视为未命中。
private const val LIVE_PROBE_CACHE_MAX_ENTRIES = 256
private const val LIVE_PROBE_CACHE_TTL_MS = 5L * 60L * 1000L

private data class ProbeCacheValue(
    val mime: String,
    val insertedAtMs: Long,
)

@Volatile
private var probeCacheInsertedCount = 0

private val probeCache = Collections.synchronizedMap(
    object : LinkedHashMap<String, ProbeCacheValue>(LIVE_PROBE_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProbeCacheValue>?): Boolean {
            val evicted = size > LIVE_PROBE_CACHE_MAX_ENTRIES
            if (evicted) probeCacheInsertedCount++
            return evicted
        }
    },
)

/** 取出缓存命中值并刷新访问计数；未命中或已过期返回 null。 */
private fun probeCacheGet(key: String): String? {
    val nowMs = System.currentTimeMillis()
    return probeCache[key]?.takeIf { nowMs - it.insertedAtMs < LIVE_PROBE_CACHE_TTL_MS }
        ?.let { probeCache[key] = it; it.mime }
}

/** 写入缓存并按 LRU 淘汰超限条目。 */
private fun probeCachePut(key: String, mime: String) {
    probeCache[key] = ProbeCacheValue(mime, System.currentTimeMillis())
    if (probeCache.size > LIVE_PROBE_CACHE_MAX_ENTRIES) {
        probeCache.remove(probeCache.entries.firstOrNull()?.key)
    }
}

/** 清理已过期的缓存条目（在每次探测时调用）。 */
private fun pruneStaleProbeCache(nowMs: Long) {
    probeCache.entries.removeAll { (_, value) -> nowMs - value.insertedAtMs >= LIVE_PROBE_CACHE_TTL_MS }
}

private fun probeCacheKey(url: String, headers: Map<String, String>): String = buildString {
    append(url)
    if (headers.isNotEmpty()) {
        append('|')
        headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedBy { it.key.lowercase() }
            .joinTo(this) { "${it.key.lowercase()}=${it.value}" }
    }
}

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
    val cacheKey = probeCacheKey(url, headers)
    pruneStaleProbeCache(System.currentTimeMillis())
    probeCacheGet(cacheKey)?.let { return it }
    val probed = withContext(Dispatchers.IO) {
        try {
            probeBlocking(url, headers)
        } catch (cancelled: CancellationException) {
            // 切台/离页取消不能被当成「探测失败」继续走降级取流。
            throw cancelled
        } catch (_: Throwable) {
            LIVE_MIME_UNKNOWN
        }
    }
    // 失败/无法判定不入缓存：避免一次网络抖动让该地址永久失去探测与黑屏修复机会。
    if (probed != LIVE_MIME_UNKNOWN) probeCachePut(cacheKey, probed)
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
    headers.sanitizedForHttp().forEach { (key, value) ->
        builder.header(key, value)
    }
    HttpClients.probe.executeCancellable(builder.build()).use { response ->
        if (!response.isSuccessful) return LIVE_MIME_UNKNOWN
        val contentType = response.header("Content-Type")
        // OkHttp 的 response.body 契约上非空（非 2xx 已由上方 isSuccessful 过滤），直接取用即可。
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
    // 识别并跳过 UTF-8/16/32 BOM（EF BB BF、FE FF、FF FE、00 00 FE FF、FF FE 00 00），
    // 并按对应编码解码正文头部，否则 UTF-16 编码的 m3u8 会被判为未知类型。
    var skip = 0
    var charset: Charset = Charsets.ISO_8859_1
    when {
        length >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() && buffer[2] == 0xBF.toByte() -> skip = 3
        length >= 4 && buffer[0] == 0x00.toByte() && buffer[1] == 0x00.toByte() &&
            buffer[2] == 0xFE.toByte() && buffer[3] == 0xFF.toByte() -> {
            skip = 4
            charset = utf32Charset("UTF-32BE")
        }
        length >= 4 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xFE.toByte() &&
            buffer[2] == 0x00.toByte() && buffer[3] == 0x00.toByte() -> {
            skip = 4
            charset = utf32Charset("UTF-32LE")
        }
        length >= 2 && buffer[0] == 0xFE.toByte() && buffer[1] == 0xFF.toByte() -> {
            skip = 2
            charset = Charsets.UTF_16BE
        }
        length >= 2 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xFE.toByte() -> {
            skip = 2
            charset = Charsets.UTF_16LE
        }
    }
    val head = String(buffer, skip, minOf(length - skip, 64), charset)
    val trimmed = head.trimStart(' ', '\t', '\r', '\n')
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

/** Android 并非所有版本都注册 UTF-32 字符集，缺失时回退 ISO-8859-1（仅影响探测结果，不影响播放）。 */
private fun utf32Charset(name: String): Charset =
    runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)

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
    val (latency, _) = probeLiveLineLatencyWithConfidence(url, headers)
    return latency
}

/**
 * T1-04：HEAD 优先测速，失败后 GET Range:0-65535 兜底。
 *
 * 先尝试 HEAD：CDN 通常支持 HEAD，无需下载任何字节即可获得 Content-Length / Content-Range，
 * 便于计算 [DurationConfidence]；当服务端拒绝 HEAD（405/404）时退回到 GET 小范围读取。
 * 返回 Pair<延迟毫秒, 时长可信度>，任何阶段失败均返回 (null, NONE)。
 */
suspend fun probeLiveLineLatencyWithConfidence(
    url: String,
    headers: Map<String, String> = emptyMap(),
): Pair<Long?, DurationConfidence> {
    if (!url.startsWith("http", ignoreCase = true)) return null to DurationConfidence.NONE
    return withContext(Dispatchers.IO) {
        try {
            // ── Step 1：先发 HEAD，省流量，同时拿响应形状算置信度 ──
            val headBuilder = Request.Builder().head().url(url)
            headers.sanitizedForHttp().forEach { (key, value) -> headBuilder.header(key, value) }
            var confidence = DurationConfidence.NONE
            // HEAD 本身无 body，用 HEAD RTT 作为延迟近似值：请求发出前记起点，响应回来算差值。
            // 此前这里漏记起点、直接返回了 System.currentTimeMillis() 绝对值，
            // 导致 HEAD 成功的线路测速结果异常偏大（约 1.7e12 ms），排序/展示全错。
            val headStartedAt = System.currentTimeMillis()
            HttpClients.probe.executeCancellable(headBuilder.build()).use { response ->
                when {
                    response.isSuccessful -> {
                        confidence = shapeFromResponse(response).let(::durationConfidence)
                    }
                    response.code == 405 || response.code == 404 -> {
                        // 服务端不支持 HEAD，降级到 GET Range 兜底
                        confidence = probeGetRangeFallback(url, headers)?.let(::shapeFromResponse)?.let(::durationConfidence) ?: DurationConfidence.NONE
                    }
                    else -> return@withContext null to DurationConfidence.NONE
                }
            }
            // HEAD 成功但未提供 content-range/content-length（多数流媒体场景），落到 GET 取实际首字节延迟
            if (confidence != DurationConfidence.NONE) {
                return@withContext (System.currentTimeMillis() - headStartedAt) to confidence
            }
            // 兜底 GET：读首字节计时
            probeGetRangeFallback(url, headers)?.let { resp ->
                val startedAt = System.currentTimeMillis()
                resp.body.byteStream().use { stream ->
                    val first = stream.read()
                    if (first >= 0) {
                        return@withContext (System.currentTimeMillis() - startedAt) to confidence
                    }
                }
            }
            null to confidence
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null to DurationConfidence.NONE
        }
    }
}

/** GET Range:0-65535 兜底探活：读首字节计时并解析响应形状。 */
private suspend fun probeGetRangeFallback(
    url: String,
    headers: Map<String, String>,
): Response? = withContext(Dispatchers.IO) {
    runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-$LIVE_PROBE_FALLBACK_RANGE_END")
        headers.sanitizedForHttp().forEach { (key, value) -> builder.header(key, value) }
        HttpClients.probe.executeCancellable(builder.build())
    }.getOrNull()?.takeIf { it.isSuccessful }
}

/** 从响应中提取 [ResponseShape]，供 [DurationConfidence] 计算使用。 */
private fun shapeFromResponse(response: Response): ResponseShape = ResponseShape(
    status = response.code,
    contentRange = response.header("Content-Range"),
    contentLength = response.header("Content-Length")?.toLongOrNull(),
    transferEncoding = response.header("Transfer-Encoding"),
)
