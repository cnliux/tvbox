package com.tvbox.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface MacCmsApi {
    suspend fun getVod(
        action: String,
        page: Int? = null,
        typeId: Int? = null,
        keyword: String? = null,
        hours: Int? = null,
        ids: String? = null,
    ): MacCmsResponse
}

object MacCmsNetwork {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val apis = ConcurrentHashMap<String, MacCmsApi>()

    fun api(baseUrl: String): MacCmsApi {
        return apis.computeIfAbsent(baseUrl) {
            val endpoint = baseUrl.trim()
                .let { if (it.endsWith('/')) it else "$it/" }
                .toHttpUrlOrNull()
                ?: throw IllegalArgumentException("无效的视频接口地址：$baseUrl")
            object : MacCmsApi {
                override suspend fun getVod(
                    action: String,
                    page: Int?,
                    typeId: Int?,
                    keyword: String?,
                    hours: Int?,
                    ids: String?,
                ): MacCmsResponse {
                    val url = endpoint.newBuilder().apply {
                        addQueryParameter("ac", action)
                        page?.let { addQueryParameter("pg", it.toString()) }
                        typeId?.let { addQueryParameter("t", it.toString()) }
                        keyword?.takeIf { it.isNotBlank() }?.let { addQueryParameter("wd", it) }
                        hours?.let { addQueryParameter("h", it.toString()) }
                        ids?.takeIf { it.isNotBlank() }?.let { addQueryParameter("ids", it) }
                    }.build()
                    val request = Request.Builder().url(url).get().build()
                    return executeCancellable(request)
                }
            }
        }
    }

    /**
     * 可取消的 OkHttp 调用：协程取消时立即 cancel Call，避免请求继续阻塞到 readTimeout。
     * 上层 `withTimeoutOrNull`（搜索/详情分源超时）因此能真正到点生效。
     */
    private suspend fun executeCancellable(request: Request): MacCmsResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                throw IOException("视频接口 HTTP ${resp.code}：${request.url}")
                            }
                            json.decodeFromString<MacCmsResponse>(resp.body.string())
                        }
                    }
                    if (continuation.isActive) {
                        result.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = { continuation.resumeWithException(it) },
                        )
                    }
                }
            })
        }
}

// 项目不再内置任何点播接口：视频源完全由用户在源管理中导入（customVideoApiLines）。
