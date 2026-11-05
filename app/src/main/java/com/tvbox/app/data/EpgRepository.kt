package com.tvbox.app.data

import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.parseXmltvEpg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface EpgRepository {
    /** 拉取并解析 XMLTV；失败返回空 Map（不抛异常，EPG 是增强项不阻塞主流程）。 */
    suspend fun fetch(epgUrl: String): Map<String, List<EpgProgram>>
}

/**
 * 远端 XMLTV 仓库：OkHttp 拉取（gzip 由 OkHttp 透明处理）+ 内存缓存。
 *
 * 缓存 key = url，TTL 30 分钟；Mutex 让并发同 URL 的请求只发一次，
 * 第二个进入临界区的调用直接命中缓存。
 */
class RemoteEpgRepository : EpgRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedEpg>()

    override suspend fun fetch(epgUrl: String): Map<String, List<EpgProgram>> {
        val normalized = epgUrl.trim()
        val httpUrl = normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        // URL 空/非 http 直接返回空 Map。
        if (normalized.isBlank() || !httpUrl) return emptyMap()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val cached = cache[normalized]
                val now = System.currentTimeMillis()
                if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS) return@withContext cached.programs
                val text = runCatching { download(normalized) }.getOrNull()
                if (text.isNullOrBlank()) {
                    // 失败也降级返回旧缓存（若有），EPG 缺失不阻塞直播主流程。
                    return@withContext cached?.programs ?: emptyMap()
                }
                val parsed = runCatching { parseXmltvEpg(text) }.getOrDefault(emptyMap())
                cache[normalized] = CachedEpg(parsed, now)
                parsed
            }
        }
    }

    private fun download(url: String): String {
        // 与 SourceConfigFetcher 同口径的 UA 标识。
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) TVBox-Android/1.3")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            val body = response.body
            // 上限保护：XMLTV 全量接口通常几 MB～几十 MB，40MB 以上视为异常源。
            val stream = body.byteStream()
            val out = java.io.ByteArrayOutputStream(
                if (body.contentLength() in 1..MAX_PAYLOAD_BYTES) body.contentLength().toInt() else 16_384,
            )
            val buffer = ByteArray(16_384)
            var size = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                size += read
                if (size > MAX_PAYLOAD_BYTES) break
                out.write(buffer, 0, read)
            }
            var data = out.toByteArray()
            // 去 UTF-8 BOM，避免影响正则首段匹配。
            if (data.size >= 3 && data[0].toInt() == 0xEF && data[1].toInt() == 0xBB && data[2].toInt() == 0xBF) {
                data = data.copyOfRange(3, data.size)
            }
            String(data, Charsets.UTF_8)
        }
    }

    private data class CachedEpg(val programs: Map<String, List<EpgProgram>>, val fetchedAtMs: Long)

    private companion object {
        const val CACHE_TTL_MS = 30L * 60L * 1000L
        const val MAX_PAYLOAD_BYTES = 40L * 1024L * 1024L
    }
}
