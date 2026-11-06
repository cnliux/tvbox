package com.tvbox.app.data

import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.data.network.parseNetworkPayload
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

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
                    return HttpClients.default
                        .executeCancellable(request, NetworkRetryPolicy.IdempotentGet)
                        .use { response ->
                            parseNetworkPayload { json.decodeFromString<MacCmsResponse>(response.body.string()) }
                        }
                }
            }
        }
    }
}

// 项目不再内置任何点播接口：视频源完全由用户在源管理中导入（customVideoApiLines）。
