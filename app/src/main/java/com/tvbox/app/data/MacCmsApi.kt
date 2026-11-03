package com.tvbox.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    private val apis = mutableMapOf<String, MacCmsApi>()

    fun api(baseUrl: String): MacCmsApi {
        return apis.getOrPut(baseUrl) {
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
                ): MacCmsResponse = withContext(Dispatchers.IO) {
                    val url = endpoint.newBuilder().apply {
                        addQueryParameter("ac", action)
                        page?.let { addQueryParameter("pg", it.toString()) }
                        typeId?.let { addQueryParameter("t", it.toString()) }
                        keyword?.takeIf { it.isNotBlank() }?.let { addQueryParameter("wd", it) }
                        hours?.let { addQueryParameter("h", it.toString()) }
                        ids?.takeIf { it.isNotBlank() }?.let { addQueryParameter("ids", it) }
                    }.build()
                    val request = Request.Builder().url(url).get().build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("视频接口 HTTP ${response.code}：$url")
                        }
                        json.decodeFromString<MacCmsResponse>(response.body.string())
                    }
                }
            }
        }
    }
}

// 项目不再内置任何点播接口：视频源完全由用户在源管理中导入（customVideoApiLines）。
