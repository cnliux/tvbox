package com.tvbox.app.ai

import com.tvbox.app.BuildConfig
import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.UserAgentSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** AI 未命中后的引导动作，来自设置项 aiMissBehavior（stay / search）。 */
enum class AiMissBehavior(val storageKey: String) {
    Stay("stay"),
    Search("search"),
    ;

    companion object {
        fun fromStorageKey(value: String?): AiMissBehavior =
            entries.firstOrNull { it.storageKey == value } ?: Stay
    }
}

/** AI 只给出候选片名与年份，不携带任何播放地址。 */
data class AiCandidate(
    val title: String,
    val year: String? = null,
)

/** 由 AppSettings 派生的一次检索配置；缓存与脱敏行为都由此决定。 */
data class AiSearchConfig(
    val enabled: Boolean = true,
    val redactText: Boolean = true,
    val onlyTitleYear: Boolean = true,
    val missBehavior: String = "stay",
    /** 缓存分钟数；<=0 表示禁用缓存。 */
    val cacheMinutes: Int = 30,
) {
    companion object {
        fun from(settings: AppSettings): AiSearchConfig = AiSearchConfig(
            enabled = settings.aiSearchEnabled,
            redactText = settings.aiTextRedactionEnabled,
            onlyTitleYear = settings.aiOnlyTitleYearEnabled,
            missBehavior = settings.aiMissBehavior,
            cacheMinutes = settings.aiResultCacheMinutes,
        )
    }
}

data class AiSearchResult(
    val candidates: List<AiCandidate>,
    val missBehavior: AiMissBehavior,
    val fromCache: Boolean = false,
)

/** 所有失败都以带明确文案的异常抛出，由 UI 转成可读提示，不直接跳播放器。 */
sealed class AiSearchException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Disabled : AiSearchException("AI 找片未开启，请先在设置中打开该能力。")

    class MissingApiKey : AiSearchException(
        "构建期未注入 API Key：请在 local.properties 配置 TVBOX_AI_API_KEY 后重新构建。",
    )

    class InvalidQuery : AiSearchException("描述为空或脱敏后没有可用内容，请换个说法再试。")

    class Network(message: String, cause: Throwable) : AiSearchException(message, cause)

    class Parse(message: String, cause: Throwable? = null) : AiSearchException(message, cause)
}

/**
 * AI 找片仓库：走项目已有 OkHttp 出口（[HttpClients.default]）与 kotlinx.serialization，
 * 不引入任何新依赖。只向 OpenAI 兼容的 `/chat/completions` 请求候选片名/年份，
 * 不解析播放地址、不触发播放。
 *
 * API Key / Base URL 由构建期注入（BuildConfig），不写入偏好、不进仓库。
 */
class AiSearchRepository(
    private val apiKey: String = BuildConfig.AI_API_KEY,
    private val baseUrl: String = BuildConfig.AI_BASE_URL,
    private val client: OkHttpClient = HttpClients.default,
    private val cache: AiResultCache = AiResultLruCache(),
    private val json: Json = AiJson,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val model: String = DEFAULT_MODEL,
) {
    val apiKeyConfigured: Boolean get() = apiKey.isNotBlank()

    /** 仅供 UI 只读展示，不泄露 Key 内容。 */
    fun apiKeySourceLabel(): String = if (apiKeyConfigured) {
        "API Key：构建期注入（local.properties）"
    } else {
        "API Key：未注入（local.properties 未配置 TVBOX_AI_API_KEY）"
    }

    suspend fun search(query: String, config: AiSearchConfig = AiSearchConfig()): AiSearchResult =
        withContext(Dispatchers.IO) {
            if (!config.enabled) throw AiSearchException.Disabled()
            if (!apiKeyConfigured) throw AiSearchException.MissingApiKey()

            val userText = redactUserText(query, config.redactText)
            if (userText.isEmpty()) throw AiSearchException.InvalidQuery()

            val missBehavior = AiMissBehavior.fromStorageKey(config.missBehavior)
            val cacheKey = cacheKey(userText, config)
            val cacheTtlMs = if (config.cacheMinutes > 0) {
                config.cacheMinutes.coerceIn(1, MAX_CACHE_MINUTES) * MINUTE_MS
            } else {
                0L
            }
            if (cacheTtlMs > 0L) {
                cache.get(cacheKey, nowMs())?.let { return@withContext it.copy(fromCache = true) }
            }

            val content = requestCompletions(userText, config.onlyTitleYear)
            val candidates = parseCandidates(content)
            val result = AiSearchResult(candidates = candidates, missBehavior = missBehavior)
            // 只缓存成功结果；空结果不缓存，便于用户换个描述立刻重试。
            if (cacheTtlMs > 0L && candidates.isNotEmpty()) {
                cache.put(cacheKey, result, nowMs() + cacheTtlMs)
            }
            result
        }

    private suspend fun requestCompletions(userText: String, onlyTitleYear: Boolean): String {
        val endpoint = baseUrl.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        val trimmed = endpoint.trimEnd('/')
        val url = if (trimmed.endsWith(CHAT_COMPLETIONS_PATH, ignoreCase = true)) {
            trimmed
        } else {
            trimmed + CHAT_COMPLETIONS_PATH
        }
        val requestBody = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(ROLE_SYSTEM, systemPrompt(onlyTitleYear)),
                ChatMessage(ROLE_USER, userText),
            ),
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", JSON_MEDIA_TYPE_VALUE)
            .header("Accept", "application/json")
            .header("User-Agent", UserAgentSetting.Default)
            .post(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.executeCancellable(request).use { response ->
                val raw = response.body?.string().orEmpty()
                try {
                    json.decodeFromString<ChatResponse>(raw)
                        .choices.firstOrNull()?.message?.content.orEmpty()
                } catch (error: Throwable) {
                    throw AiSearchException.Parse("AI 返回的 JSON 无法解析，请稍后重试。", error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AiSearchException) {
            throw error
        } catch (error: NetworkException.Http) {
            throw AiSearchException.Network("AI 服务请求失败（HTTP ${error.statusCode}），请稍后重试。", error)
        } catch (error: NetworkException) {
            throw AiSearchException.Network("网络失败：${error.message ?: "请检查网络连接"}", error)
        } catch (error: Throwable) {
            throw AiSearchException.Network("网络失败：${error.message ?: "请检查网络连接"}", error)
        }
    }

    private fun cacheKey(userText: String, config: AiSearchConfig): String = buildString {
        append("ai:").append(model).append(':')
        append(if (config.redactText) '1' else '0').append(':')
        append(if (config.onlyTitleYear) '1' else '0').append(':')
        append(userText)
    }

    private companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val MAX_CACHE_MINUTES = 24 * 60
        const val MINUTE_MS = 60_000L
        const val JSON_MEDIA_TYPE_VALUE = "application/json; charset=utf-8"
        val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_VALUE.toMediaType()
    }
}

/** 系统提示词：开启 onlyTitleYear 时强制只输出片名+年份；关闭时允许带一句理由，但仓库仍然只取片名与年份。 */
private fun systemPrompt(onlyTitleYear: Boolean): String = if (onlyTitleYear) {
    "你是影视检索助手。根据用户描述推断最可能的影视剧，只输出 JSON 数组，不要解释、不要 markdown。" +
        "数组元素格式：{\"title\":\"片名\",\"year\":\"2024\"}；年份不确定时 year 传空字符串。" +
        "最多 8 条，按匹配度排序。只输出片名与年份，不要输出人名、历史信息、剧情介绍或播放地址。"
} else {
    "你是影视检索助手。根据用户描述推断最可能的影视剧，只输出 JSON 数组，不要 markdown。" +
        "数组元素格式：{\"title\":\"片名\",\"year\":\"2024\",\"reason\":\"简短理由\"}；年份不确定时 year 传空字符串。" +
        "最多 8 条，按匹配度排序。不要输出播放地址。"
}

/**
 * 文本脱敏：发送前剔除链接、账号、联系方式与个人历史/人名提示，
 * 避免把观看历史或身份信息带到第三方模型。仅做保守的正则裁剪，不影响片名本身。
 */
internal fun redactUserText(raw: String, enabled: Boolean): String {
    var text = raw.trim().replace(Regex("""\s+"""), " ").take(MAX_USER_TEXT_LENGTH)
    if (!enabled) return text
    text = URL_PATTERN.replace(text, " ")
    text = EMAIL_PATTERN.replace(text, " ")
    text = ID_CARD_PATTERN.replace(text, " ")
    text = PHONE_PATTERN.replace(text, " ")
    // 只剔除「我看过 / 我的播放记录里」这类历史提示词，不吞掉后面的片名本身。
    HISTORY_CUES.forEach { cue ->
        text = text.replace(cue, " ", ignoreCase = true)
    }
    text = PERSON_HINT_PATTERN.replace(text, " ")
    return text.replace(Regex("""\s+"""), " ").trim()
}

/** 解析模型输出：优先 JSON 数组，失败时退化为逐行「片名（年份）」文本。 */
internal fun parseCandidates(content: String): List<AiCandidate> {
    if (content.isBlank()) return emptyList()
    val fromJson = runCatching {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return@runCatching emptyList()
        AiJson.decodeFromString<List<AiCandidateDto>>(content.substring(start, end + 1))
            .mapNotNull { sanitizeCandidate(it.title, it.year) }
    }.getOrNull().orEmpty()
    if (fromJson.isNotEmpty()) return fromJson.distinctBy { it.title }.take(MAX_CANDIDATES)

    return content.lineSequence()
        .mapNotNull { line -> parsePlainLine(line) }
        .distinctBy { it.title }
        .take(MAX_CANDIDATES)
        .toList()
}

private fun parsePlainLine(line: String): AiCandidate? {
    val trimmed = line.trim().trimStart('-', '*', '•').trim()
    if (trimmed.isBlank() || trimmed.startsWith('{') || trimmed.startsWith('[')) return null
    val year = YEAR_PATTERN.find(trimmed)?.value
    val titlePart = if (year == null) trimmed else YEAR_PATTERN.replace(trimmed, " ")
    return sanitizeCandidate(titlePart, year)
}

private fun sanitizeCandidate(rawTitle: String, rawYear: String?): AiCandidate? {
    val title = rawTitle.orEmpty()
        .replace(NUMBER_PREFIX_PATTERN, " ")
        .replace(BRACKET_PATTERN, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim(' ', '.', ',', '，', '、', ';', '；', ':', '：', '-', '—')
        .take(MAX_TITLE_LENGTH)
    if (title.isBlank()) return null
    val year = YEAR_PATTERN.find(rawYear.orEmpty())?.value
        ?: YEAR_PATTERN.find(rawTitle)?.value
    return AiCandidate(title = title, year = year)
}

/** 内存 LRU 缓存：用纯 Kotlin LinkedHashMap 实现，便于 JVM 单测（不依赖 android.util.LruCache 桩）。 */
interface AiResultCache {
    fun get(key: String, nowMs: Long): AiSearchResult?
    fun put(key: String, result: AiSearchResult, expiresAtMs: Long)
    fun clear()
}

class AiResultLruCache(private val maxEntries: Int = 64) : AiResultCache {
    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(maxEntries.coerceAtLeast(1), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries.coerceAtLeast(1)
    }

    override fun get(key: String, nowMs: Long): AiSearchResult? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        if (entry.expiresAtMs > nowMs) entry.result else {
            entries.remove(key)
            null
        }
    }

    override fun put(key: String, result: AiSearchResult, expiresAtMs: Long) {
        synchronized(lock) { entries[key] = Entry(result, expiresAtMs) }
    }

    override fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private data class Entry(val result: AiSearchResult, val expiresAtMs: Long)
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int = 500,
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)

@Serializable
private data class AiCandidateDto(val title: String = "", val year: String? = null)

private val AiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

private const val MAX_USER_TEXT_LENGTH = 200
private const val MAX_TITLE_LENGTH = 80
private const val MAX_CANDIDATES = 8
private val YEAR_PATTERN = Regex("""(?:19|20)\d{2}""")
private val NUMBER_PREFIX_PATTERN = Regex("""^\s*\d+\s*[.、．)]\s*""")
private val BRACKET_PATTERN = Regex("""[\[\]{}（）()《》〈〉「」『』"'“”‘’]""")
private val URL_PATTERN = Regex("""\bhttps?://\S+|\bwww\.\S+""", RegexOption.IGNORE_CASE)
private val EMAIL_PATTERN = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
private val PHONE_PATTERN = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
private val ID_CARD_PATTERN = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
/** 历史/身份提示词：只命中提示语本身，保留其后的片名描述。 */
private val HISTORY_CUES = listOf(
    "我之前看过", "我以前看过", "我曾经看过", "我最近看过", "我看过一部", "我看过",
    "我的播放记录里", "我的浏览记录里", "我的收藏里", "我的历史里",
    "播放记录里", "浏览记录里", "收藏记录里", "历史记录里",
    "根据我的历史", "根据历史记录", "从我的收藏", "我收藏的",
)
/** 仅剔除显式标注的人名（如「主演：张三」），不误伤「周星驰的电影」这类有效描述。 */
private val PERSON_HINT_PATTERN = Regex("""(演员|主演|导演|编剧|歌手)\s*[:：]\s*[\w\u4e00-\u9fa5·]{2,10}""")
