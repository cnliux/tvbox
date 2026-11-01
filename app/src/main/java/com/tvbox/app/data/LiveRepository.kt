package com.tvbox.app.data

import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.parseLiveChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

interface LiveRepository {
    suspend fun getChannels(): List<LiveChannel>

    /**
     * 更新电视直播源配置。
     *
     * @param customUrls 用户添加的自定义 M3U/TXT 源地址。
     */
    fun setSourceConfig(customUrls: List<String>)
}

class DefaultLiveRepository : LiveRepository {
    private var customSourceUrls: List<String> = emptyList()

    override fun setSourceConfig(customUrls: List<String>) {
        this.customSourceUrls = customUrls
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun getChannels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val sourceUrls = customSourceUrls.map(::normalizeSourceUrl).distinct()
        if (sourceUrls.isEmpty()) {
            throw IOException("未添加任何直播源，请在源管理中添加自定义源")
        }
        var lastError: Throwable? = null
        sourceUrls.forEach { sourceUrl ->
            try {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("直播源加载失败：${response.code}")
                    }
                    val channels = parseLiveChannels(response.body.string())
                    if (channels.isNotEmpty()) return@withContext channels
                    throw IOException("直播源未返回可用频道")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException("所有直播源均加载失败", lastError)
    }
}

/**
 * 归一化用户输入的直播源地址：手填时常见把文件地址误写成目录（如 `xxx.m3u/`），
 * 直接请求会返回 404。这里只对明显是具体文件（.m3u/.m3u8/.txt/.json）的地址去掉尾部斜杠。
 */
internal fun normalizeSourceUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || !trimmed.endsWith('/')) return trimmed
    val withoutSlash = trimmed.trimEnd('/')
    if (withoutSlash.isEmpty()) return trimmed
    val path = withoutSlash.substringBefore('?').substringBefore('#').lowercase()
    val isFile = path.endsWith(".m3u") || path.endsWith(".m3u8") ||
        path.endsWith(".txt") || path.endsWith(".json")
    return if (isFile) withoutSlash else trimmed
}
