package com.tvbox.app.data

import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.extractM3uEpgUrl
import com.tvbox.app.domain.parseLiveChannels
import com.tvbox.app.util.logSafeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

private const val LIVE_SOURCE_TAG = "TVBoxLiveSource"

/** 单个直播订阅的最大读取量：误填成目录页或超大文件时避免整份文本进入内存。 */
internal const val LIVE_SOURCE_MAX_BYTES = 20L * 1024L * 1024L

interface LiveRepository {
    suspend fun getChannels(): List<LiveChannel>

    /**
     * 更新电视直播源配置。
     *
     * @param customUrls 用户添加的自定义 M3U/TXT 源地址；内部会归一化、去空、去重，
     *   [sourceUrls] 返回的就是这份最终列表，展示与选源共用同一套下标。
     * @param defaultGroup 解析成功后，默认将频道分组名改为该值（避免"电视直播"兜底名）。
     * @param defaultIndex 默认源在原始 customUrls 中的下标（-1 表示无默认）；
     *   按 URL 值重映射到最终列表，重复/空白项被移除后仍指向同一个订阅。
     */
    fun setSourceConfig(customUrls: List<String>, defaultGroup: String, defaultIndex: Int)

    /**
     * 选择仅加载某一个订阅地址（直播页订阅切换）。
     *
     * @param index [sourceUrls] 中的下标；-1 表示按默认源优先**逐个回退**尝试全部订阅
     *   （首个解析出频道的源生效，不做跨源合并，避免不同订阅的频道号互相污染）。
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
        val previousUrls = this.customSourceUrls
        val previousSelection = this.selectionIndex
        // 归一化/去空/去重一次性完成：展示标签、默认源与选中源共用同一份索引，不再出现错位。
        val normalized = customUrls.map(::normalizeSourceUrl).filter { it.isNotBlank() }.distinct()
        val defaultUrl = customUrls.getOrNull(defaultIndex)?.let(::normalizeSourceUrl)
        val selectedUrl = previousSelection.takeIf { it >= 0 }?.let { previousUrls.getOrNull(it) }
        this.customSourceUrls = normalized
        this.defaultGroup = defaultGroup
        this.defaultIndex = defaultUrl?.let { normalized.indexOf(it) } ?: -1
        this.selectionIndex = selectedUrl?.let { normalized.indexOf(it) } ?: -1
    }

    override fun setSourceSelection(index: Int) {
        this.selectionIndex = index
    }

    override val sourceUrls: List<String> get() = customSourceUrls

    private var lastBundledEpgUrl: String? = null

    override val bundledEpgUrl: String? get() = lastBundledEpgUrl

    override suspend fun getChannels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val allUrls = customSourceUrls
        if (allUrls.isEmpty()) {
            throw IOException("请添加自定义直播源地址后重试")
        }
        val orderedUrls = resolveSourceOrder(allUrls, defaultIndex, selectionIndex)
        var lastError: Throwable? = null
        var lastLoadError: String? = null

        orderedUrls.forEach { sourceUrl ->
            try {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .build()
                val rawText = try {
                    HttpClients.default.executeCancellable(request, NetworkRetryPolicy.IdempotentGet)
                        .use { response -> readLimitedLiveSourceText(response.body.byteStream()) }
                } catch (error: NetworkException.Http) {
                    lastError = IOException("直播源加载失败：${error.statusCode}")
                    lastLoadError = "直播源加载失败（HTTP ${error.statusCode}）"
                    android.util.Log.w(LIVE_SOURCE_TAG, "【源加载失败】${logSafeUrl(sourceUrl)} HTTP ${error.statusCode}")
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
                        "【源加载成功】${logSafeUrl(sourceUrl)} channels=${channels.size} lines=${channels.sumOf { it.lines.size }}",
                    )
                    return@withContext channels
                }
                lastError = IOException("直播源未返回可用频道")
                lastLoadError = "直播源未返回可用频道"
                android.util.Log.w(LIVE_SOURCE_TAG, "【源无频道】${logSafeUrl(sourceUrl)} 解析结果为空（HTML 目录页或格式不识别）")
                throw IOException("直播源未返回可用频道")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException(
            if (defaultIndex in orderedUrls.indices) {
                "默认直播源及备用源均不可用（${lastLoadError ?: "网络异常"}）"
            } else {
                "所有直播源均加载失败（${lastLoadError ?: "网络异常"}）"
            },
            lastError,
        )
    }
}

/**
 * 按「选中订阅 → 默认源优先 → 其余订阅」确定请求顺序；纯函数便于单测锁定下标语义。
 * 选中单个订阅时不再把默认源下标套到单元素列表上。
 */
internal fun resolveSourceOrder(
    allUrls: List<String>,
    defaultIndex: Int,
    selectionIndex: Int,
): List<String> {
    val candidates = if (selectionIndex in allUrls.indices) {
        listOf(allUrls[selectionIndex])
    } else {
        allUrls
    }
    if (selectionIndex in allUrls.indices || defaultIndex !in candidates.indices) return candidates
    return buildList {
        add(candidates[defaultIndex])
        addAll(candidates.filterIndexed { i, _ -> i != defaultIndex })
    }
}

/** 限量读取直播源正文；超过上限直接失败，避免异常订阅把内存打满。 */
internal fun readLimitedLiveSourceText(
    stream: InputStream,
    maxBytes: Long = LIVE_SOURCE_MAX_BYTES,
): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16_384)
    var size = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        size += read
        if (size > maxBytes) throw IOException("直播源内容超过体积上限，已跳过解析")
        out.write(buffer, 0, read)
    }
    val data = out.toByteArray()
    return if (data.size >= 3 && data[0].toInt() == 0xEF && data[1].toInt() == 0xBB && data[2].toInt() == 0xBF) {
        String(data.copyOfRange(3, data.size), Charsets.UTF_8)
    } else {
        String(data, Charsets.UTF_8)
    }
}

/**
 * 归一化用户输入的直播源地址：手填时常见把文件地址误写成目录（如 `xxx.m3u/`），
 * 直接请求会返回 404 或 HTML 目录页。这里去掉结尾多余的 `/`，
 * 其余（含 query/fragment）保持不变。
 */
internal fun normalizeSourceUrl(raw: String): String = raw.trim().trimEnd('/')
