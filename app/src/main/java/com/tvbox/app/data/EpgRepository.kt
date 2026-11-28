package com.tvbox.app.data

import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.parseXmltvEpg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

interface EpgRepository {
    /**
     * 拉取并解析 XMLTV；失败返回空 Map（不抛异常，EPG 是增强项不阻塞主流程）。
     * [userAgent]：空串回退内置默认；[timezoneId]：无时区后缀时间戳的解释时区（空跟随设备）。
     */
    suspend fun fetch(
        epgUrl: String,
        userAgent: String = "",
        timezoneId: String = "",
    ): Map<String, List<EpgProgram>>
}

/**
 * 远端 XMLTV 仓库：OkHttp 拉取（gzip 由 OkHttp 透明处理）+ 内存缓存。
 *
 * 缓存 key = url，TTL 30 分钟；Mutex 让并发同 URL 的请求只发一次，
 * 第二个进入临界区的调用直接命中缓存。缓存条目有数量上限并会清理过期项，
 * 避免长期使用后多份大节目单常驻内存。
 */
class RemoteEpgRepository : EpgRepository {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedEpg>()

    override suspend fun fetch(
        epgUrl: String,
        userAgent: String,
        timezoneId: String,
    ): Map<String, List<EpgProgram>> {
        val normalized = epgUrl.trim()
        val httpUrl = normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        // URL 空/非 http 直接返回空 Map。
        if (normalized.isBlank() || !httpUrl) return emptyMap()
        // 缓存 key 同时带时区：同一 URL 在不同时区下解析结果不同（无时区后缀时间戳）。
        val tzKey = timezoneId.trim()
        val cacheKey = "$normalized\u0000$tzKey"
        val resolvedUa = userAgent.trim().ifBlank {
            com.tvbox.app.domain.UserAgentSetting.Default
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val cached = cache[cacheKey]
                val now = System.currentTimeMillis()
                if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS) return@withContext cached.programs
                val text = try {
                    download(normalized, resolvedUa)
                } catch (cancelled: CancellationException) {
                    // 换源/离页取消时不能把取消误判成「网络失败」并继续占用 Mutex。
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (text.isNullOrBlank()) {
                    // 失败也降级返回旧缓存（若有），EPG 缺失不阻塞直播主流程。
                    return@withContext cached?.programs ?: emptyMap()
                }
                val parsed = try {
                    parseXmltvEpg(text, tzKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return@withContext cached?.programs ?: emptyMap()
                }
                // 只有解析出节目时才写入缓存：空结果常来自 HTML 错误页 / DTD 拒绝 / XMLTV 无 programme，
                // 缓存它会让空节目单在 TTL 内被反复命中、切网络/换频道都不重拉，EPG 永久空白。
                // 空结果不缓存：下次进入可重拉，且旧缓存（若有）仍可降级展示。
                if (parsed.isNotEmpty()) {
                    cache.entries.removeIf { now - it.value.fetchedAtMs >= CACHE_TTL_MS }
                    if (cache.size >= MAX_CACHE_ENTRIES) {
                        cache.entries.minByOrNull { it.value.fetchedAtMs }?.key?.let { cache.remove(it) }
                    }
                    cache[cacheKey] = CachedEpg(parsed, now)
                }
                parsed
            }
        }
    }

    private suspend fun download(url: String, userAgent: String): String {
        // 与 SourceConfigFetcher 同口径的 UA 标识；UA 作用域（epg）由调用方通过参数传入。
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        return HttpClients.default.executeCancellable(request, NetworkRetryPolicy.IdempotentGet).use { response ->
            val body = response.body
            // 上限保护：XMLTV 全量接口通常几 MB～几十 MB，超限视为异常源，
            // 不再把截断到标签中间的 XML 当成成功结果写入缓存。
            val stream = body.byteStream()
            val out = java.io.ByteArrayOutputStream(
                if (body.contentLength() in 1..MAX_PAYLOAD_BYTES) body.contentLength().toInt() else 16_384,
            )
            val buffer = ByteArray(16_384)
            var size = 0L
            while (true) {
                val read = stream.read(buffer)
                // read == 0 只是暂时无数据（非阻塞流也可能出现），不能当作 EOF 提前截断。
                if (read < 0) break
                if (read == 0) continue
                size += read
                if (size > MAX_PAYLOAD_BYTES) throw IOException("节目单超过体积上限，已跳过解析")
                out.write(buffer, 0, read)
            }
            val data = out.toByteArray()
            // gzip / BOM 解码收敛为纯函数，便于用真实 .xml.gz 字节做 JVM 回归。
            decodeEpgPayload(data)
        }
    }

    private data class CachedEpg(val programs: Map<String, List<EpgProgram>>, val fetchedAtMs: Long)

    private companion object {
        const val CACHE_TTL_MS = 30L * 60L * 1000L
        const val MAX_PAYLOAD_BYTES = 40L * 1024L * 1024L
        const val MAX_CACHE_ENTRIES = 8
    }
}

/**
 * 探测 gzip magic：前两字节 `0x1F 0x8B`（RFC 1952）。至少 2 字节才判断，
 * 空响应/单字节不算 gzip。
 *
 * 关键：Kotlin 的 `Byte` 是有符号的，`0x8B` 作为字节等于 -117，直接写
 * `data[1].toInt() == 0x8B` 永远为 false——老张 EPG 的 `.xml.gz` 会因此
 * 被当成明文 UTF-8 解码成乱码、解析出空节目单。必须用 `and 0xFF` 转无符号。
 */
internal fun isGzipEpgPayload(data: ByteArray): Boolean =
    data.size >= 2 &&
        (data[0].toInt() and 0xFF) == 0x1F &&
        (data[1].toInt() and 0xFF) == 0x8B

/**
 * 应用层 gzip 解压 + 去 UTF-8 BOM，返回 XMLTV 明文。
 *
 * 解压后的明文 XMLTV 才是真正进内存的内容，与下载阶段的 [MAX_PAYLOAD_BYTES]
 * 同口径保护——`.gz` 是压缩格式，下载侧上限只约束压缩字节，解压会内存放大，
 * 因此解压过程同样按上限截断并在超限时抛 IOException，由调用方走降级路径。
 */
internal fun decodeEpgPayload(data: ByteArray): String {
    val decompressed = if (isGzipEpgPayload(data)) gunzipEpgPayload(data) else data
    // 去 UTF-8 BOM：用 (offset,length) 构造字符串，避免 copyOfRange 再全量拷贝
    // 一份 40MB 数组（低端盒子峰值内存可省约 40MB）。BOM 三字节同样要按无符号比较。
    val hasBom = decompressed.size >= 3 &&
        (decompressed[0].toInt() and 0xFF) == 0xEF &&
        (decompressed[1].toInt() and 0xFF) == 0xBB &&
        (decompressed[2].toInt() and 0xFF) == 0xBF
    return if (hasBom) {
        String(decompressed, 3, decompressed.size - 3, Charsets.UTF_8)
    } else {
        String(decompressed, Charsets.UTF_8)
    }
}

private fun gunzipEpgPayload(data: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(data)).use { input ->
        val out = java.io.ByteArrayOutputStream(if (data.size > 0) data.size * 8 else 16_384)
        val buffer = ByteArray(16_384)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            size += read
            if (size > MAX_EPG_PAYLOAD_BYTES) throw IOException("节目单解压后超过体积上限，已跳过解析")
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }

private const val MAX_EPG_PAYLOAD_BYTES = 40L * 1024L * 1024L
