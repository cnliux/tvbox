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
import java.io.IOException

interface EpgRepository {
    /** 拉取并解析 XMLTV；失败返回空 Map（不抛异常，EPG 是增强项不阻塞主流程）。 */
    suspend fun fetch(epgUrl: String): Map<String, List<EpgProgram>>
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
                val text = try {
                    download(normalized)
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
                    parseXmltvEpg(text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return@withContext cached?.programs ?: emptyMap()
                }
                cache.entries.removeIf { now - it.value.fetchedAtMs >= CACHE_TTL_MS }
                if (cache.size >= MAX_CACHE_ENTRIES) {
                    cache.entries.minByOrNull { it.value.fetchedAtMs }?.key?.let { cache.remove(it) }
                }
                cache[normalized] = CachedEpg(parsed, now)
                parsed
            }
        }
    }

    private suspend fun download(url: String): String {
        // 与 SourceConfigFetcher 同口径的 UA 标识。
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) TVBox-Android/1.3")
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
            // 去 UTF-8 BOM：用 (offset,length) 构造字符串，避免 copyOfRange 再全量拷贝
            // 一份 40MB 数组（低端盒子峰值内存可省约 40MB）。
            val hasBom = data.size >= 3 &&
                data[0].toInt() == 0xEF && data[1].toInt() == 0xBB && data[2].toInt() == 0xBF
            if (hasBom) {
                String(data, 3, data.size - 3, Charsets.UTF_8)
            } else {
                String(data, Charsets.UTF_8)
            }
        }
    }

    private data class CachedEpg(val programs: Map<String, List<EpgProgram>>, val fetchedAtMs: Long)

    private companion object {
        const val CACHE_TTL_MS = 30L * 60L * 1000L
        const val MAX_PAYLOAD_BYTES = 40L * 1024L * 1024L
        const val MAX_CACHE_ENTRIES = 8
    }
}
