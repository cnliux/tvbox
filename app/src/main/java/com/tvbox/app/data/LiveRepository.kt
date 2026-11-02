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
     * @param defaultGroup 解析成功后，默认将频道分组名改为该值（避免"电视直播"兜底名）。
     * @param defaultIndex 默认源下标（-1 表示无默认，按顺序尝试）；默认源不可用时按原顺序回退备用源。
     */
    fun setSourceConfig(customUrls: List<String>, defaultGroup: String, defaultIndex: Int)
}

class DefaultLiveRepository : LiveRepository {
    private var customSourceUrls: List<String> = emptyList()
    private var defaultGroup: String = ""
    private var defaultIndex: Int = -1

    override fun setSourceConfig(customUrls: List<String>, defaultGroup: String, defaultIndex: Int) {
        this.customSourceUrls = customUrls
        this.defaultGroup = defaultGroup
        this.defaultIndex = if (defaultIndex in customUrls.indices) defaultIndex else -1
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun getChannels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val sourceUrls = customSourceUrls
            .map(::normalizeSourceUrl)
            .filter { it.isNotBlank() }
            .distinct()
        if (sourceUrls.isEmpty()) {
            throw IOException("请添加自定义直播源地址后重试")
        }
        var lastError: Throwable? = null
        var lastLoadError: String? = null

        // 优先尝试默认源，再尝试其他源；确保默认源失败时有明确提示。
        val orderedUrls = if (defaultIndex in sourceUrls.indices) {
            buildList {
                add(sourceUrls[defaultIndex])
                addAll(sourceUrls.filterIndexed { i, _ -> i != defaultIndex })
            }
        } else {
            sourceUrls
        }

        orderedUrls.forEach { sourceUrl ->
            try {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = IOException("直播源加载失败：${response.code}")
                        lastLoadError = "直播源加载失败（HTTP ${response.code}）"
                        return@forEach
                    }
                    val channels = parseLiveChannels(response.body.string())
                        .map { ch ->
                            if (ch.groupName == "电视直播" && defaultGroup.isNotBlank()) ch.copy(groupName = defaultGroup) else ch
                        }
                        .distinctBy { ch -> ch.name }
                        .withIndex()
                        .map { (i, ch) -> ch.copy(number = i + 1) }
                    if (channels.isNotEmpty()) return@withContext channels
                    lastError = IOException("直播源未返回可用频道")
                    lastLoadError = "直播源未返回可用频道"
                    throw IOException("直播源未返回可用频道")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException(
            if (defaultIndex in sourceUrls.indices) "默认直播源不可用（${lastLoadError ?: "网络异常"}），已切换备用源"
            else "所有直播源均加载失败",
            lastError,
        )
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
