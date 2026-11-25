package com.tvbox.app.data.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** T1-05：DoH 隐私三档——默认关闭，绝不把用户源域名悄悄发给第三方解析器。 */
enum class DohMode {
    /** 只用系统 DNS，行为与接线前完全一致。 */
    OFF,
    /** 系统 DNS 失败/超时后才启用 DoH（默认推荐档，需用户显式开启）。 */
    FALLBACK,
    /** 优先 DoH，失败再回落系统 DNS。 */
    PREFER,
}

/** T1-05/06：分层解析的可调口径真源，避免各处散落魔法数。 */
object ParallelDnsDefaults {
    /** DoH 使用 JSON API（OkHttp + kotlinx.serialization 即可，不引入新依赖）。 */
    val DOH_TEMPLATES: List<String> = listOf(
        "https://dns.google/resolve",
        "https://cloudflare-dns.com/dns-query",
    )
    const val CACHE_TTL_MS: Long = 60_000L
    const val RACE_DELAY_MS: Long = 250L
    const val SYSTEM_TIMEOUT_MS: Long = 1_000L
    const val DOH_TIMEOUT_MS: Long = 2_000L
    const val CONNECT_PORT: Int = 443
}

/** T1-06：简化的地址连通性探针；注入假实现即可在 JVM 单测中验证竞速，不碰真实 socket。 */
fun interface AddressConnector {
    /** 返回 true 表示连接成功；实现方自行关闭资源。 */
    fun canConnect(address: InetAddress, port: Int, timeoutMs: Int): Boolean
}

private val SOCKET_CONNECTOR = AddressConnector { address, port, timeoutMs ->
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(address, port), timeoutMs.coerceAtLeast(1))
        true
    } catch (_: java.io.IOException) {
        false
    } finally {
        runCatching { socket.close() }
    }
}

private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

/**
 * T1-05/06：分层 DNS 解析 + 简化版 Happy Eyeballs。
 *
 * 层次：缓存快路径 →（可选）静态 hosts / L0 系统 DNS（短超时）→ L1 DoH →
 * L2 备用 DoH → 过期缓存兜底。默认三档全关时 [lookup] 直接委托 [Dns.SYSTEM]，
 * 不改变现有行为；DoH 默认 OFF，开启后才会把域名发给 [ParallelDnsDefaults.DOH_TEMPLATES]。
 *
 * Happy Eyeballs（RFC8305 简化）：IPv6/IPv4 各取首个地址并发起连接探测，
 * 250ms 内先连通者排到返回列表首位，输的 future 立即 cancel；都没连通则保持系统顺序。
 */
class ParallelDns(
    private val policy: NetworkPolicy = NetworkPolicies.default,
    private val systemDns: Dns = Dns.SYSTEM,
    /** 注入假 DoH 解析器用于单测；null 时走内置 JSON API 实现。 */
    private val dohLookup: ((template: String, host: String, type: Int) -> List<InetAddress>)? = null,
    private val connector: AddressConnector = SOCKET_CONNECTOR,
    private val executor: ExecutorService = SHARED_EXECUTOR,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Dns {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** DoH 服务自身仍需系统 DNS 引导，故该 client 不挂本类，避免循环解析。 */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(policy.dnsSystemTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            .readTimeout(policy.dohTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            .callTimeout(
                (policy.dnsSystemTimeoutMs + policy.dohTimeoutMs).coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) throw UnknownHostException("空主机名")

        // 向后兼容：三档全关时与 Dns.SYSTEM 直通，不缓存、不竞速、不访问 DoH。
        if (policy.dohMode == DohMode.OFF && !policy.dnsHappyEyeballs && policy.dohHosts.isEmpty()) {
            return systemDns.lookup(host)
        }

        val cached = cache[host]
        // 缓存快路径：命中且未过期直接返回，仍按竞速结果排序。
        if (cached != null && cached.expiresAtMs > nowMs()) {
            return reorderForRace(cached.addresses)
        }

        var resolved: List<InetAddress> = emptyList()

        // L2（静态 hosts）：最高优先于网络解析，便于用户为已知域名固定 IP。
        val fromHosts = policy.dohHosts[host]
            ?.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
            .orEmpty()
        if (fromHosts.isNotEmpty()) resolved = fromHosts

        // PREFER 档：先问 DoH 主/备，失败再回落系统 DNS。
        if (resolved.isEmpty() && policy.dohMode == DohMode.PREFER) {
            resolved = queryDoh(host)
        }

        // L0：系统 DNS，短超时；超时按失败处理，交给下一层。
        if (resolved.isEmpty()) {
            val fromSystem = runWithTimeout(policy.dnsSystemTimeoutMs) { systemDns.lookup(host) }
            if (!fromSystem.isNullOrEmpty()) resolved = fromSystem
        }

        // FALLBACK / PREFER 回落：系统失败后才启用 DoH（隐私默认档的行为）。
        if (resolved.isEmpty() && policy.dohMode != DohMode.OFF) {
            resolved = queryDoh(host)
        }

        // L3 兜底：所有解析都失败时用过期缓存，避免握手期短暂 DNS 故障直接断流。
        if (resolved.isEmpty() && cached != null) resolved = cached.addresses
        if (resolved.isEmpty()) throw UnknownHostException(host)

        cache[host] = CacheEntry(resolved, nowMs() + policy.dnsCacheTtlMs.coerceAtLeast(0L))
        return reorderForRace(resolved)
    }

    /** 主 DoH 模板失败后自动切备用模板，A / AAAA 都查以便 Happy Eyeballs 竞速。 */
    private fun queryDoh(host: String): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        for (template in policy.dohTemplates) {
            for (type in listOf(1, 28)) {
                val found = runWithTimeout(policy.dohTimeoutMs) { performDohLookup(template, host, type) }
                if (!found.isNullOrEmpty()) result.addAll(found)
            }
            if (result.isNotEmpty()) break
        }
        return result.distinct()
    }

    private fun performDohLookup(template: String, host: String, type: Int): List<InetAddress> =
        if (dohLookup != null) dohLookup(template, host, type) else queryDohJson(template, host, type)

    /** JSON API 解析（RFC8484 wire format 需自行封包/解包，这里用纯文本 JSON 避免新依赖）。 */
    private fun queryDohJson(template: String, host: String, type: Int): List<InetAddress> {
        val trimmed = template.trim().trimEnd('?', '&')
        val separator = if (trimmed.contains('?')) '&' else '?'
        val url = "$trimmed$separator" + "name=${URLEncoder.encode(host, "UTF-8")}&type=$type"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .header("User-Agent", "TVBox-DoH/1.0")
            .get()
            .build()
        val body = dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()
        val answers = runCatching {
            Json.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return answers.mapNotNull { answer ->
            runCatching {
                val obj = answer.jsonObject
                val answerType = obj["type"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@runCatching null
                if (answerType != type) return@runCatching null
                val data = obj["data"]?.jsonPrimitive?.content.orEmpty()
                InetAddress.getByName(data)
            }.getOrNull()
        }
    }

    /** 简化版 Happy Eyeballs：IPv6/IPv4 各取首个地址，胜者前置，输的取消。 */
    private fun reorderForRace(addresses: List<InetAddress>): List<InetAddress> {
        if (!policy.dnsHappyEyeballs || addresses.size < 2) return addresses
        val v6 = addresses.firstOrNull { it is Inet6Address }
        val v4 = addresses.firstOrNull { it is Inet4Address }
        if (v6 == null || v4 == null) return addresses

        val winner = AtomicReference<InetAddress>()
        val done = CountDownLatch(1)
        val timeoutMs = policy.dnsRaceDelayMs.coerceAtLeast(1L).toInt()
        val futures = listOf(v6, v4).map { address ->
            submit {
                if (connector.canConnect(address, policy.dnsConnectPort, timeoutMs)) {
                    if (winner.compareAndSet(null, address)) done.countDown()
                }
            }
        }
        done.await(policy.dnsRaceDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        // 输的（以及超时未返回的）立即取消，避免探测 socket 堆积。
        futures.forEach { it.cancel(true) }
        val preferred = winner.get() ?: return addresses
        return listOf(preferred) + addresses.filterNot { it == preferred }
    }

    private fun <T> submit(block: () -> T): Future<T> = executor.submit(Callable(block))

    private fun <T> runWithTimeout(timeoutMs: Long, block: () -> T): T? {
        val future = submit(block)
        return try {
            future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private companion object {
        val SHARED_EXECUTOR: ExecutorService by lazy {
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "tvbox-parallel-dns").apply { isDaemon = true }
            }
        }
    }
}
