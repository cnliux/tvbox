package com.tvbox.app.data.network

import java.io.IOException
import java.io.InterruptedIOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局自定义 User-Agent 持有点：由 ViewModel 在设置加载/变更时推入（已按
 * [com.tvbox.app.domain.UserAgentSetting.resolve] 收敛为非空默认值），
 * 视频接口请求、订阅拉取等各网络出口按需读取；直播播放的线路级 UA 仍优先生效。
 */
object GlobalUserAgent {
    @Volatile
    var value: String = ""

    /**
     * S2-11：按目标域取 UA。GitHub / OTA 等保留域强制用内置 UA，
     * 防止自定义 UA 把唯一的更新通道弄断（即使全局值已设坏）。
     */
    fun valueForHost(host: String): String =
        if (com.tvbox.app.domain.UaScope.isImmutableHost(host)) {
            com.tvbox.app.domain.UserAgentSetting.Default
        } else {
            value
        }
}

/**
 * 全局 OkHttpClient 出口：统一连接/读/写/整次调用超时口径，避免各仓库各写一套。
 * 需要特殊口径时用 [default.newBuilder] 派生，不要直接 new Builder。
 */
object HttpClients {
    /**
     * T1-07：全仓唯一重试仲裁器。所有经 [executeCancellable] 的重试都需向它申请预算，
     * 避免 OkHttp 盲重 × 源探测 × 播放换源 × OTA 叠加成一次点击 6~11 倍流量。
     */
    val retryArbiter: RetryArbiter = RetryArbiter()

    /** T1-02：明文守卫单例，default / probe 共用同一份会话计数。 */
    private val cleartextGuard = CleartextGuard()

    /** T1-03：跳转追踪单例，default / probe 共用同一份会话计数。 */
    private val redirectTracker = RedirectTracker()

    /** 本次会话明文/TLS/拦截计数，供设置页展示。 */
    fun cleartextStats(): CleartextStats = cleartextGuard.snapshot()

    /** T1-03：本次会话所有 3xx 跳转统计（降级/跨站计数），供设置页展示。 */
    fun redirectStats(): RedirectStats = redirectTracker.snapshot()

    /** 业务接口默认口径：连接 10s、单次读 20s、整次调用 60s。 */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(cleartextGuard)
            // T1-03：手动跟进所有跳转，拦截每一跳以便 classify / mustWarn / mustRecomputeUa。
            .addInterceptor(RedirectTrackingInterceptor(onRedirect = redirectTracker::recordHop))
            .followRedirects(false)          // 禁止 OkHttp 自动跟随，交给上方拦截器手工跟进。
            .followSslRedirects(false)
            // T1-01：显式限定连接池（5 空余 / 5 分钟），避免多 client 各自建池把弱盒 FD/内存吃满。
            .connectionPool(okhttp3.ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 直播可用性探测/测速：短超时快速失败，不重试。 */
    val probe: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS)
            .build()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 20L
    private const val CALL_TIMEOUT_SECONDS = 60L
    private const val PROBE_TIMEOUT_SECONDS = 6L
    private const val MAX_IDLE_CONNECTIONS = 5
    private const val KEEP_ALIVE_SECONDS = 300L
}

/** 网络错误分类；均继承 [IOException]，兼容既有的 catch/runCatching 逻辑。 */
sealed class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Http(val statusCode: Int, val url: String) : NetworkException("HTTP $statusCode：$url")

    class Timeout(message: String, cause: Throwable) : NetworkException(message, cause)

    class Parse(message: String, cause: Throwable) : NetworkException(message, cause)

    /** #389：HTTPS 证书校验失败，需给出明确提示而非笼统"网络失败"。 */
    class Certificate(message: String, cause: Throwable) : NetworkException(message, cause)

    class Io(message: String, cause: Throwable) : NetworkException(message, cause)
}

/** 仅幂等 GET 允许重试；间隔按 baseDelayMs 指数退避，协程取消会立即中断等待。 */
data class NetworkRetryPolicy(
    val maxRetries: Int,
    val baseDelayMs: Long,
    /** T1-07：重试预算的种类，交给 [RetryArbiter] 统一仲裁。 */
    val kind: RetryKind = RetryKind.PROBE,
) {
    init {
        require(maxRetries >= 0) { "maxRetries 不能为负" }
        require(baseDelayMs >= 0L) { "baseDelayMs 不能为负" }
    }

    companion object {
        val None = NetworkRetryPolicy(maxRetries = 0, baseDelayMs = 0L)

        /** 内容接口：最多重试 2 次（400ms、800ms）。 */
        val IdempotentGet = NetworkRetryPolicy(maxRetries = 2, baseDelayMs = 400L)

        /** OTA：预算最严，见 P2-17。 */
        val OtaDownload = NetworkRetryPolicy(maxRetries = 2, baseDelayMs = 800L, kind = RetryKind.OTA)
    }
}

/**
 * 可取消的 OkHttp 执行器：协程取消时立即 cancel Call，避免阻塞到 readTimeout。
 * 非 2xx 抛 [NetworkException.Http]；超时抛 [NetworkException.Timeout]；其余 IO 抛 [NetworkException.Io]。
 * 取消不包装，统一以 [CancellationException] 透传。
 */
suspend fun OkHttpClient.executeCancellable(
    request: Request,
    retryPolicy: NetworkRetryPolicy = NetworkRetryPolicy.None,
    /**
     * T1-07：重试仲裁器。传 null 可显式关闭仲裁（仅供需要自管预算的特殊调用方/单测使用），
     * 默认走 [HttpClients.retryArbiter] 这一个唯一决策点。
     */
    arbiter: RetryArbiter? = HttpClients.retryArbiter,
): Response {
    require(request.method == "GET" || retryPolicy.maxRetries == 0) {
        "只有幂等 GET 允许重试：${request.method} ${request.url}"
    }
    var attempt = 0
    while (true) {
        try {
            return executeOnce(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkException.Http) {
            val retryable = error.statusCode == 429 || error.statusCode in 500..599
            if (!retryable || attempt >= retryPolicy.maxRetries) throw error
        } catch (error: IOException) {
            if (attempt >= retryPolicy.maxRetries) throw error.toNetworkException(request.url.toString())
        }
        // 单次重试次数上限由 retryPolicy 控制；跨调用、跨模块的全局预算由 arbiter 控制。
        // 预算用尽时直接抛出最后一次错误，绝不在这里自行 repeat。
        if (arbiter != null && !arbiter.permit(request.url.host, retryPolicy.kind)) {
            throw NetworkException.Io(
                "重试预算已用尽：${request.url.host} / ${retryPolicy.kind}",
                IOException("RetryArbiter denied"),
            )
        }
        attempt++
        delay(retryPolicy.baseDelayMs * (1L shl (attempt - 1)))
    }
}

private suspend fun OkHttpClient.executeOnce(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e.toNetworkException(request.url.toString()))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                if (response.isSuccessful) {
                    continuation.resume(response)
                } else {
                    val code = response.code
                    response.close()
                    continuation.resumeWithException(NetworkException.Http(code, request.url.toString()))
                }
            }
        })
    }

private fun IOException.toNetworkException(url: String): NetworkException = when (this) {
    is NetworkException -> this
    is InterruptedIOException -> NetworkException.Timeout(message ?: "请求超时：$url", this)
    // #389：证书错误单独分类。沿 cause 链找证书相关异常，命中即给明确文案。
    is SSLException -> if (isCertificateError(this)) {
        NetworkException.Certificate("HTTPS 证书校验失败，请检查设备时间或网站证书：$url", this)
    } else {
        NetworkException.Io(message ?: "网络请求失败：$url", this)
    }
    else -> NetworkException.Io(message ?: "网络请求失败：$url", this)
}

private fun isCertificateError(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is SSLHandshakeException ||
            current is CertPathValidatorException ||
            current is CertificateExpiredException ||
            current is CertificateNotYetValidException ||
            current.message?.contains("certificate", ignoreCase = true) == true ||
            current.message?.contains("trust anchor", ignoreCase = true) == true
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/** 包装响应解析异常，统一归类为 [NetworkException.Parse]；取消照常透传。 */
inline fun <T> parseNetworkPayload(block: () -> T): T =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw NetworkException.Parse(error.message ?: "响应解析失败", error)
    }

/** T1-03：本次会话跳转统计快照。 */
data class RedirectStats(
    val totalHops: Int = 0,
    /** HTTPS → HTTP 降级跳数（隐私风险最高）。 */
    val downgradeHops: Int = 0,
    /** 跨站跳数（eTLD+1 变化）。 */
    val crossSiteHops: Int = 0,
)

/**
 * T1-03：跳转追踪单例，持有累计计数并在 [RedirectTrackingInterceptor] 每一跳回调时累加。
 */
class RedirectTracker {
    private val totalHops = AtomicInteger(0)
    private val downgradeHops = AtomicInteger(0)
    private val crossSiteHops = AtomicInteger(0)

    /** 每发现一个 3xx 跳转调用一次，内部按 [RedirectPolicy.classify] 分类并计数。 */
    fun recordHop(hop: RedirectPolicy.Hop) {
        when (RedirectPolicy.classify(hop)) {
            RedirectPolicy.Risk.DOWNGRADE -> {
                totalHops.incrementAndGet()
                downgradeHops.incrementAndGet()
            }
            RedirectPolicy.Risk.CROSS_SITE -> {
                totalHops.incrementAndGet()
                crossSiteHops.incrementAndGet()
            }
            else -> totalHops.incrementAndGet()
        }
    }

    fun snapshot(): RedirectStats = RedirectStats(
        totalHops = totalHops.get(),
        downgradeHops = downgradeHops.get(),
        crossSiteHops = crossSiteHops.get(),
    )
}

/**
 * T1-03：手动跟进所有 3xx 跳转的应用层拦截器。
 *
 * 通过禁用 OkHttp 自动重定向（[OkHttpClient.Builder.followRedirects]/[followSslRedirects]），
 * 本拦截器在链中捕获每一次 3xx 响应，按 [RedirectPolicy.classify] 分类并调用 [onRedirect]。
 * 循环跟进直到拿到非 3xx 响应，再把最终响应交还给后续链（包括 [cleartextGuard] 等）。
 *
 * 注意：由于禁用了自动重定向，[executeCancellable] 收到的将始终是最终目标响应
 * （非 3xx），不会因中间 3xx 抛出异常。
 */
class RedirectTrackingInterceptor(
    private val onRedirect: (RedirectPolicy.Hop) -> Unit = {},
) : okhttp3.Interceptor {
    private val maxHops = 8

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        repeat(maxHops) {
            val response = chain.proceed(request)
            when (response.code) {
                301, 302, 303, 307, 308 -> {
                    val location = response.header("Location")
                    if (location.isNullOrBlank()) {
                        return@intercept response
                    }
                    val hop = RedirectPolicy.Hop(request.url.toString(), location)
                    onRedirect(hop)
                    response.close()
                    // 构建下一跳请求
                    val nextBuilder = request.newBuilder().url(location)
                    // 303 规定必须改为 GET
                    if (response.code == 303) {
                        request = nextBuilder.method("GET", null).build()
                    } else {
                        request = nextBuilder.build()
                    }
                }
                else -> return response
            }
        }
        // 超过上限后退回最后一次响应（让上层决定如何处理）。
        return chain.proceed(request)
    }
}
