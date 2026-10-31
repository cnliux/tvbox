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

/** 内置电视直播源：可在源管理页逐条删除/恢复，并决定是否与自定义源组合。 */
data class BuiltInLiveSource(
    val id: String,
    val name: String,
    val url: String,
)

object BuiltInLiveSources {
    val all: List<BuiltInLiveSource> = listOf(
        BuiltInLiveSource(
            id = "tvbox-default",
            name = "内置直播源",
            url = "http://20.205.10.127:8787/tvbox/result.txt",
        ),
    )

    fun byId(id: String): BuiltInLiveSource? = all.firstOrNull { it.id == id }
}

interface LiveRepository {
    suspend fun getChannels(): List<LiveChannel>

    /**
     * 更新电视直播源配置。
     *
     * @param builtIn 参与组合的内置源（已排除被删除的条目）。
     * @param customUrls 自定义 M3U/TXT 源地址。
     * @param includeBuiltIn 是否把内置源与自定义源组合使用；关闭时只用自定义源。
     */
    fun setSourceConfig(
        builtIn: List<BuiltInLiveSource>,
        customUrls: List<String>,
        includeBuiltIn: Boolean,
    )
}

class DefaultLiveRepository(
    initialBuiltInSources: List<BuiltInLiveSource> = BuiltInLiveSources.all,
) : LiveRepository {
    private var builtInSources: List<BuiltInLiveSource> = initialBuiltInSources
    private var customSourceUrls: List<String> = emptyList()
    private var includeBuiltInSources: Boolean = true

    override fun setSourceConfig(
        builtIn: List<BuiltInLiveSource>,
        customUrls: List<String>,
        includeBuiltIn: Boolean,
    ) {
        this.builtInSources = builtIn
        this.customSourceUrls = customUrls
        this.includeBuiltInSources = includeBuiltIn
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun getChannels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val sourceUrls = buildList {
            if (includeBuiltInSources) addAll(builtInSources.map { it.url })
            addAll(customSourceUrls)
        }.distinct()
        if (sourceUrls.isEmpty()) {
            throw IOException("未启用任何直播源，请在源管理中启用内置源或添加自定义源")
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
