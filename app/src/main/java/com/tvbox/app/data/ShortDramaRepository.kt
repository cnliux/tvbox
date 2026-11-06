package com.tvbox.app.data

import com.tvbox.app.domain.ShortDrama
import com.tvbox.app.domain.ShortDramaCatalogPage
import com.tvbox.app.domain.ShortDramaDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 短剧数据源（红果短剧网页）。 */
interface ShortDramaRepository {
    /** 分类列表页；[category] 为源里的分类路由，默认「真人剧」。 */
    suspend fun getCatalog(page: Int, category: String = DEFAULT_CATEGORY): ShortDramaCatalogPage

    /** 剧集详情（含分集 ID 列表）。 */
    suspend fun getDetail(seriesId: String): ShortDramaDetail?

    /**
     * 解析某一集的真实播放地址；无可用地址返回 null（前端降级提示，不阻塞列表浏览）。
     */
    suspend fun resolvePlayUrl(seriesId: String, episodeId: String): String?
}

const val DEFAULT_CATEGORY = "real-drama"

/**
 * 红果短剧仓库：直接解析网页内联的 `_ROUTER_DATA` JSON。
 *
 * 站点没有公开 API 文档，列表/详情都渲染在同一份 `_ROUTER_DATA` 里，
 * 因此按「提取 JSON → 取对应 loader 节点」的方式读取，避免依赖 HTML 结构。
 * 播放页受 `protectedPlayback` 保护，地址需经代理接口换取；该接口不可用时
 * 返回 null 让 UI 降级提示，不影响列表与详情浏览。
 */
class HongguoShortDramaRepository(
    private val baseUrl: String = "https://hongguoduanju.com",
    private val playbackEndpoint: String = "https://djapi.999888456.xyz/api/hongguo/play",
) : ShortDramaRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun getCatalog(page: Int, category: String): ShortDramaCatalogPage =
        withContext(Dispatchers.IO) {
            val safePage = page.coerceAtLeast(1)
            val html = fetchText("$baseUrl/category/$category?page=$safePage")
            val router = parseRouterData(html)
            val node = router
                ?.optJSONObject("loaderData")
                ?.let { loaders -> findLoader(loaders, listOf("category_page", "category_")) }
                ?: return@withContext ShortDramaCatalogPage(emptyList(), safePage, 1)
            if (node.optBoolean("isSuccess", true).not()) {
                return@withContext ShortDramaCatalogPage(emptyList(), safePage, 1)
            }
            val list = node.optJSONArray("recommendList") ?: JSONArray()
            val items = buildList {
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.toShortDrama()?.let { add(it) }
                }
            }.distinctBy { it.id }
            val totalPages = node.optJSONObject("pagination")
                ?.optInt("totalPages", 1)
                ?.coerceAtLeast(1) ?: 1
            ShortDramaCatalogPage(items = items, page = safePage, totalPages = totalPages)
        }

    override suspend fun getDetail(seriesId: String): ShortDramaDetail? = withContext(Dispatchers.IO) {
        if (seriesId.isBlank()) return@withContext null
        val html = fetchText("$baseUrl/detail?series_id=$seriesId")
        val router = parseRouterData(html) ?: return@withContext null
        val node = router
            .optJSONObject("loaderData")
            ?.let { loaders -> findLoader(loaders, listOf("detail_page", "detail_")) }
            ?: return@withContext null
        val detail = node.optJSONObject("seriesDetail") ?: return@withContext null
        val drama = detail.toShortDrama() ?: return@withContext null
        // 分集：vid_list 按下标即第 N 集；缺失时按 episode_cnt 生成空占位由 UI 提示。
        val vids = detail.optJSONArray("vid_list")
        val episodeIds = buildList {
            if (vids != null) {
                for (i in 0 until vids.length()) {
                    vids.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        ShortDramaDetail(drama = drama, episodeIds = episodeIds)
    }

    override suspend fun resolvePlayUrl(seriesId: String, episodeId: String): String? =
        withContext(Dispatchers.IO) {
            if (seriesId.isBlank() || episodeId.isBlank()) return@withContext null
            // 播放页本身是受保护的（videoList 为空），这里直接走代理接口换取地址。
            val reference = buildPlayReference(seriesId, episodeId)
            val url = "$playbackEndpoint?id=" + java.net.URLEncoder.encode(reference, "UTF-8")
            val body = runCatching { fetchText(url) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            runCatching {
                val json = JSONObject(body)
                // parse/jx 非 0 表示解析失败；url 回显请求串同样是失败。
                val failed = listOf("parse", "jx").any { key ->
                    val value = json.opt(key)
                    value != null && value != JSONObject.NULL && value.toString() !in setOf("false", "0", "")
                }
                if (failed) return@runCatching null
                val direct = json.optString("url")
                if (direct.isBlank() || direct == reference) return@runCatching null
                direct
            }.getOrNull()
        }

    private fun buildPlayReference(seriesId: String, episodeId: String): String {
        val payload = JSONObject()
            .put("content_type", 1004)
            .put("from_video_id", "")
            .put("series_id", seriesId)
            .put("vid", episodeId)
            .put("video_platform", 3)
            .toString()
        return android.util.Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            return response.body.string()
        }
    }

    private companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

/** 取首个命中的 loader 节点：精确名优先，其次前缀匹配（`category_xxx`）。 */
internal fun findLoader(loaders: JSONObject, names: List<String>): JSONObject? {
    for (name in names) {
        loaders.optJSONObject(name)?.let { if (it.length() > 0) return it }
    }
    val keys = loaders.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (names.any { prefix -> key.startsWith(prefix.trimEnd('$')) }) {
            loaders.optJSONObject(key)?.let { if (it.length() > 0) return it }
        }
    }
    return null
}

/**
 * 从 HTML 中提取 `_ROUTER_DATA = {...}` 的 JSON 对象。
 *
 * 不用正则匹配括号（字符串内的括号会破坏平衡），改为逐字符扫描并跟踪
 * 引号/转义状态，找到与起始花括号配对的结束位置。
 */
internal fun parseRouterData(html: String): JSONObject? {
    val marker = Regex("(?:_ROUTER_DATA)\\s*=\\s*").find(html) ?: return null
    val start = marker.range.last + 1
    if (start >= html.length || html[start] != '{') return null
    var depth = 0
    var quoted = false
    var escaped = false
    var i = start
    while (i < html.length) {
        val c = html[i]
        if (quoted) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> quoted = false
            }
        } else {
            when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(html.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        i++
    }
    return null
}

/** 列表项/详情节点的统一映射：字段既可能在顶层，也可能在 `video_data` 内。 */
internal fun JSONObject.toShortDrama(): ShortDrama? {
    val nested = optJSONObject("video_data")
    fun str(vararg keys: String): String {
        for (key in keys) {
            val v = optString(key).takeIf { it.isNotBlank() && it != "null" }
            if (v != null) return v
            nested?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        return ""
    }

    val id = str("series_id_str", "series_id", "keyword")
    if (id.isBlank()) return null
    val title = str("series_name", "series_title", "title", "name").ifBlank { id }
    val tags = mutableListOf<String>()
    (optJSONArray("tags") ?: nested?.optJSONArray("tags"))?.let { arr ->
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { tags += it }
        }
    }
    val count = str("episode_cnt").toIntOrNull() ?: 0
    return ShortDrama(
        id = id,
        title = title,
        cover = str("series_cover", "cover"),
        intro = str("series_intro", "video_desc"),
        category = tags.firstOrNull().orEmpty(),
        tags = tags,
        episodeCount = count,
        remark = str("episode_right_text"),
    )
}
