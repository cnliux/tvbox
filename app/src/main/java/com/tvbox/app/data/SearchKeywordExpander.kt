package com.tvbox.app.data

import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.data.network.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

interface SearchKeywordExpander {
    /**
     * 将拼音首字母（如 xclf）扩展为候选中文片名。
     * 非拼音首字母输入返回空列表，调用方回退为原始关键词搜索。
     */
    suspend fun expandInitials(
        query: String,
        limit: Int = QuantitySettings.SearchSuggestionCount.default,
    ): List<String>
}

class DefaultSearchKeywordExpander(
    private val client: OkHttpClient = HttpClients.probe,
    private val endpoint: String = SUGGEST_ENDPOINT,
) : SearchKeywordExpander {
    override suspend fun expandInitials(query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (!isPinyinInitialQuery(normalized)) {
            return@withContext emptyList()
        }
        runCatching {
            val request = Request.Builder()
                .url("$endpoint?word=$normalized&encodein=utf-8&encodeout=utf-8")
                .header("User-Agent", MOBILE_USER_AGENT)
                .build()
            client.executeCancellable(request).use { response ->
                val body = response.body.string().orEmpty()
                extractTitleCandidates(parseSuggestionWords(body), limit = limit)
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val SUGGEST_ENDPOINT = "https://sug.so.360.cn/suggest"
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}


private val suggestionJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class SuggestionResponse(val result: List<SuggestionItem> = emptyList())

@Serializable
private data class SuggestionItem(val word: String = "")

internal fun parseSuggestionWords(rawResponse: String): List<String> =
    runCatching {
        suggestionJson.decodeFromString<SuggestionResponse>(rawResponse).result.map { it.word }
    }.getOrDefault(emptyList())

private val pinyinInitialPattern = Regex("[a-zA-Z]{2,12}")

/** 只接受纯字母且长度 2~12 的输入，避免把英文片名或中文误判为拼音首字母。 */
internal fun isPinyinInitialQuery(query: String): Boolean =
    pinyinInitialPattern.matches(query.trim())

private val titleNoiseMarkers = listOf(
    "在线观看",
    "免费观看",
    "免费版",
    "完整版",
    "高清",
    "剧情介绍",
    "分集介绍",
    "演员表",
    "百度百科",
    "第一集",
    "第二集",
    "下载",
    "星辰影院",
    "策驰影院",
    "在线看",
)

private val titleSuffixes = listOf("电视剧", "电影", "综艺", "动漫", "动画", "纪录片")

/**
 * 360 搜索建议返回的是“小城良方电视剧在线观看完整版”这类长词条，
 * 这里裁掉营销后缀并统计候选片名，出现次数越多的片名越可信。
 */
internal fun extractTitleCandidates(words: List<String>, limit: Int = 3): List<String> {
    if (words.isEmpty()) return emptyList()
    val scores = LinkedHashMap<String, Int>()
    words.forEachIndexed { index, raw ->
        val candidate = normalizeSuggestionTitle(raw) ?: return@forEachIndexed
        val weight = (words.size - index) * 2
        scores[candidate] = (scores[candidate] ?: 0) + weight
    }
    return scores.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key.length },
        )
        .map { it.key }
        .filter { it.length in 2..14 }
        .take(limit)
}

internal fun normalizeSuggestionTitle(raw: String): String? {
    var title = raw.trim()
    if (title.isBlank()) return null
    val cutIndex = titleNoiseMarkers
        .map { title.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull()
    if (cutIndex != null) {
        title = title.substring(0, cutIndex)
    }
    title = title.trim { it == ' ' || it == '·' || it == '-' || it == '，' || it == ',' || it == '。' || it == '.' }
    titleSuffixes.forEach { suffix ->
        if (title.length > suffix.length + 1 && title.endsWith(suffix)) {
            title = title.removeSuffix(suffix).trim()
        }
    }
    if (title.length < 2 || title.length > 14) return null
    if (title.none { it.code > 127 }) return null
    return title
}
