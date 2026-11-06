package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.data.network.parseNetworkPayload
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.isBlockedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 热门搜索词来源：只使用聚合榜单（360 影视热榜的电影/电视剧/综艺 + 豆瓣热播），
 * 不读取任何单个视频源站点自己的热搜，避免切换视频源后热词随源变化。
 */
interface SearchHotWordRepository {
    suspend fun getHotWords(forceRefresh: Boolean = false, limit: Int = HOT_WORD_LIMIT): List<String>
}

class DefaultSearchHotWordRepository(
    private val cache: SearchHotWordCache = InMemorySearchHotWordCache(),
    private val client: OkHttpClient = HttpClients.default,
    private val doubanTitles: suspend () -> List<String> = { emptyList() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SearchHotWordRepository {
    override suspend fun getHotWords(forceRefresh: Boolean, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, QuantitySettings.HotWordCount.max)
        val now = nowMs()
        if (!forceRefresh) {
            cache.get(now)?.words?.takeIf { it.isNotEmpty() }?.let { return@withContext it.take(safeLimit) }
        }

        val fetched = runCatching { fetchAggregatedTitles() }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            cache.put(
                SearchHotWordCacheRecord(
                    words = fetched,
                    expiresAtEpochMs = now + SUCCESS_CACHE_TTL_MS,
                ),
            )
            return@withContext fetched.take(safeLimit)
        }
        // 网络全部失败时退回上一次的聚合结果，避免热门搜索空白。
        cache.getAny()?.words.orEmpty().take(safeLimit)
    }

    private suspend fun fetchAggregatedTitles(): List<String> = coroutineScope {
        val rankRequests = HOT_RANK_CATEGORIES.map { category ->
            async { runCatching { fetchRankTitles(category) }.getOrDefault(emptyList()) }
        }
        val doubanRequest = async { runCatching { doubanTitles() }.getOrDefault(emptyList()) }
        // 缓存完整档位（最大可选值），实际展示数量由调用方按设置裁剪。
        composeHotWords((rankRequests + doubanRequest).awaitAll(), limit = QuantitySettings.HotWordCount.max)
    }

    private suspend fun fetchRankTitles(category: Int): List<String> {
        val url = HOT_RANK_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("cat", category.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HOT_RANK_USER_AGENT)
            .header("Referer", HOT_RANK_REFERER)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        val raw = try {
            client.executeCancellable(request, NetworkRetryPolicy.IdempotentGet)
                .use { response -> response.body.string() }
        } catch (error: NetworkException.Http) {
            throw IOException("聚合热搜暂时不可用（${error.statusCode}）", error)
        }
        return parseNetworkPayload { parseRankTitles(raw) }
    }
}

interface SearchHotWordCache {
    fun get(nowMs: Long): SearchHotWordCacheRecord?
    fun getAny(): SearchHotWordCacheRecord?
    fun put(record: SearchHotWordCacheRecord)
}

@Serializable
data class SearchHotWordCacheRecord(
    val words: List<String> = emptyList(),
    val expiresAtEpochMs: Long = 0L,
)

class SharedSearchHotWordCache(context: Context) : SearchHotWordCache {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun get(nowMs: Long): SearchHotWordCacheRecord? {
        return getAny()?.takeIf { it.expiresAtEpochMs > nowMs }
    }

    override fun getAny(): SearchHotWordCacheRecord? {
        val raw = preferences.getString(KEY_HOT_WORDS, null) ?: return null
        return runCatching { json.decodeFromString<SearchHotWordCacheRecord>(raw) }.getOrNull()
    }

    override fun put(record: SearchHotWordCacheRecord) {
        preferences.edit().putString(KEY_HOT_WORDS, json.encodeToString(record)).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "search_hot_word_cache"
        const val KEY_HOT_WORDS = "aggregated"
    }
}

class InMemorySearchHotWordCache : SearchHotWordCache {
    @Volatile
    private var record: SearchHotWordCacheRecord? = null

    override fun get(nowMs: Long): SearchHotWordCacheRecord? {
        return record?.takeIf { it.expiresAtEpochMs > nowMs }
    }

    override fun getAny(): SearchHotWordCacheRecord? = record

    override fun put(record: SearchHotWordCacheRecord) {
        this.record = record
    }
}

internal fun parseRankTitles(rawResponse: String): List<String> {
    return runCatching {
        searchHotWordJson.decodeFromString<RankResponse>(rawResponse).data.mapNotNull { item ->
            item.title.trim().takeIf { it.isNotBlank() && !isBlockedContent(it) }
        }
    }.getOrDefault(emptyList())
}

internal fun composeHotWords(
    titleGroups: List<List<String>>,
    limit: Int = HOT_WORD_LIMIT,
): List<String> {
    val words = LinkedHashSet<String>()
    titleGroups.forEach { group ->
        group.forEach { raw ->
            val cleaned = raw.trim().removeSurrounding("《", "》").trim()
            if (cleaned.isNotEmpty() && !isBlockedContent(cleaned)) words.add(cleaned)
        }
    }
    return words.take(limit)
}

@Serializable
private data class RankResponse(
    val data: List<RankItemDto> = emptyList(),
)

@Serializable
private data class RankItemDto(
    val title: String = "",
)

internal const val HOT_WORD_LIMIT = 18

private const val SUCCESS_CACHE_TTL_MS = 30L * 60L * 1000L
private const val HOT_RANK_ENDPOINT = "https://api.web.360kan.com/v1/rank"
private const val HOT_RANK_REFERER = "https://www.360kan.com/rank/general"
private const val HOT_RANK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; TVBox) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// 2 电影、3 电视剧、4 综艺，与聚合热榜分类一致。
private val HOT_RANK_CATEGORIES = listOf(3, 2, 4)

private val searchHotWordJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

