package com.tvbox.app.data

import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.extractM3uEpgUrl
import com.tvbox.app.domain.parseLiveChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

private const val LIVE_SOURCE_TAG = "TVBoxLiveSource"

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

    /**
     * 选择仅加载某一个订阅地址（直播页订阅切换）。
     *
     * @param index 订阅下标；-1 表示按默认源优先合并加载全部订阅。
     */
    fun setSourceSelection(index: Int) {}

    /** 当前配置的订阅地址列表，供直播页展示订阅切换入口。 */
    val sourceUrls: List<String> get() = emptyList()

    /**
     * 最近一次成功解析的源内置 EPG 地址（M3U 头部 `url-tvg` / `x-tvg-url` 等）。
     * null 表示该源未声明节目单，调用方保持用户已配置的 EPG 不变。
     */
    val bundledEpgUrl: String? get() = null
}

class DefaultLiveRepository : LiveRepository {
    private var customSourceUrls: List<String> = emptyList()
    private var defaultGroup: String = ""
    private var defaultIndex: Int = -1
    private var selectionIndex: Int = -1

    override fun setSourceConfig(customUrls: List<String>, defaultGroup: String, defaultIndex: Int) {
        this.customSourceUrls = customUrls
        this.defaultGroup = defaultGroup
        this.defaultIndex = if (defaultIndex in customUrls.indices) defaultIndex else -1
    }

    override fun setSourceSelection(index: Int) {
        this.selectionIndex = index
    }

    override val sourceUrls: List<String> get() = customSourceUrls

    private var lastBundledEpgUrl: String? = null

    override val bundledEpgUrl: String? get() = lastBundledEpgUrl

    override suspend fun getChannels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val allUrls = customSourceUrls
            .map(::normalizeSourceUrl)
            .filter { it.isNotBlank() }
            .distinct()
        if (allUrls.isEmpty()) {
            throw IOException("请添加自定义直播源地址后重试")
        }
        // 订阅切换：选中单个订阅时仅加载该地址；否则全部订阅按默认源优先。
        val sourceUrls = if (selectionIndex in allUrls.indices) {
            listOf(allUrls[selectionIndex])
        } else {
            allUrls
        }
        var lastError: Throwable? = null
        var lastLoadError: String? = null

        // 优先尝试默认源，再尝试其他源；确保默认源失败时有明确提示。
        val orderedUrls = if (selectionIndex !in allUrls.indices && defaultIndex in sourceUrls.indices) {
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
                val rawText = try {
                    HttpClients.default.executeCancellable(request, NetworkRetryPolicy.IdempotentGet)
                        .use { response -> response.body.string() }
                } catch (error: NetworkException.Http) {
                    lastError = IOException("直播源加载失败：${error.statusCode}")
                    lastLoadError = "直播源加载失败（HTTP ${error.statusCode}）"
                    android.util.Log.w(LIVE_SOURCE_TAG, "【源加载失败】$sourceUrl HTTP ${error.statusCode}")
                    return@forEach
                }
                // 源自带的节目单地址（M3U 头部 url-tvg 等）；TXT 源没有该声明，保持 null。
                lastBundledEpgUrl = runCatching { extractM3uEpgUrl(rawText) }.getOrNull()
                val channels = parseLiveChannels(rawText)
                    .map { ch ->
                        if (ch.groupName == "电视直播" && defaultGroup.isNotBlank()) ch.copy(groupName = defaultGroup) else ch
                    }
                    .withIndex()
                    .map { (i, ch) -> ch.copy(number = i + 1) }
                if (channels.isNotEmpty()) {
                    android.util.Log.i(
                        LIVE_SOURCE_TAG,
                        "【源加载成功】$sourceUrl channels=${channels.size} lines=${channels.sumOf { it.lines.size }}",
                    )
                    return@withContext channels
                }
                lastError = IOException("直播源未返回可用频道")
                lastLoadError = "直播源未返回可用频道"
                android.util.Log.w(LIVE_SOURCE_TAG, "【源无频道】$sourceUrl 解析结果为空（HTML 目录页或格式不识别）")
                throw IOException("直播源未返回可用频道")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException(
            if (defaultIndex in sourceUrls.indices) {
                "默认直播源及备用源均不可用（${lastLoadError ?: "网络异常"}）"
            } else {
                "所有直播源均加载失败（${lastLoadError ?: "网络异常"}）"
            },
            lastError,
        )
    }
}

/**
 * 归一化用户输入的直播源地址：手填时常见把文件地址误写成目录（如 `xxx.m3u/`），
 * 直接请求会返回 404 或 HTML 目录页。这里把明确的文件地址去尾部斜杠，
 * 其余把结尾多余的 `/` 也去掉（jsDelivr 之类 CDN 会把它当目录返回 HTML）。
 */
internal fun normalizeSourceUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || !trimmed.endsWith('/')) return trimmed
    val withoutSlash = trimmed.trimEnd('/')
    if (withoutSlash.isEmpty()) return trimmed
    val path = withoutSlash.substringBefore('?').substringBefore('#').lowercase()
    val isFile = path.endsWith(".m3u") || path.endsWith(".m3u8") ||
        path.endsWith(".txt") || path.endsWith(".json")
    return if (isFile) withoutSlash else withoutSlash
}
