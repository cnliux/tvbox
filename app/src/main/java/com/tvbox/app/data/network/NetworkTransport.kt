package com.tvbox.app.data.network

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局 OkHttpClient 出口：统一连接/读/写/整次调用超时口径，避免各仓库各写一套。
 * 需要特殊口径时用 [default.newBuilder] 派生，不要直接 new Builder。
 */
object HttpClients {
    /** 业务接口默认口径：连接 10s、单次读 20s、整次调用 60s。 */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
}

/** 网络错误分类；均继承 [IOException]，兼容既有的 catch/runCatching 逻辑。 */
sealed class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Http(val statusCode: Int, val url: String) : NetworkException("HTTP $statusCode：$url")

    class Timeout(message: String, cause: Throwable) : NetworkException(message, cause)

    class Parse(message: String, cause: Throwable) : NetworkException(message, cause)

    class Io(message: String, cause: Throwable) : NetworkException(message, cause)
}

/** 仅幂等 GET 允许重试；间隔按 baseDelayMs 指数退避，协程取消会立即中断等待。 */
data class NetworkRetryPolicy(val maxRetries: Int, val baseDelayMs: Long) {
    init {
        require(maxRetries >= 0) { "maxRetries 不能为负" }
        require(baseDelayMs >= 0L) { "baseDelayMs 不能为负" }
    }

    companion object {
        val None = NetworkRetryPolicy(maxRetries = 0, baseDelayMs = 0L)

        /** 内容接口：最多重试 2 次（400ms、800ms）。 */
        val IdempotentGet = NetworkRetryPolicy(maxRetries = 2, baseDelayMs = 400L)
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
    else -> NetworkException.Io(message ?: "网络请求失败：$url", this)
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
