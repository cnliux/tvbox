package com.tvbox.app.domain

data class Category(
    val id: Int,
    val parentId: Int,
    val name: String,
)

data class ApiLine(
    val id: String,
    val name: String,
    val baseUrls: List<String>,
)

data class Movie(
    val id: Int,
    val apiLineId: String,
    val apiLineName: String,
    val name: String,
    val typeId: Int,
    val typeName: String,
    val posterUrl: String,
    val remarks: String,
    val year: String,
    val area: String,
    val language: String,
    val actor: String,
    val director: String,
    val duration: String,
    val description: String,
    val playSources: List<PlaySource>,
) {
    val subtitle: String
        get() = listOf(year, area, language, remarks)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")

    fun preferredSourceIndex(): Int {
        val m3u8Index = playSources.indexOfFirst {
            it.episodes.isNotEmpty() && it.name.contains("m3u8", ignoreCase = true)
        }
        if (m3u8Index >= 0) return m3u8Index
        return playSources.indexOfFirst { it.episodes.isNotEmpty() }.coerceAtLeast(0)
    }
}

data class PlaySource(
    val name: String,
    val episodes: List<PlayEpisode>,
    val lineId: String = "",
    val lineName: String = name,
    val sourceName: String = name,
)

data class PlayEpisode(
    val title: String,
    val url: String,
)

data class PagedMovies(
    val page: Int,
    val pageCount: Int,
    val total: Int,
    val apiLine: ApiLine,
    val categories: List<Category>,
    val movies: List<Movie>,
)

data class LiveChannel(
    val number: Int,
    val groupName: String,
    val name: String,
    /** 多线路列表；单线路即 size == 1，顺序与源文件中出现顺序一致。 */
    val lines: List<LiveChannelLine>,
    /** 频道台标 URL，来自 M3U `tvg-logo` 等源扩展字段；空表示无台标，UI 需降级为首字占位。 */
    val logo: String = "",
    /**
     * XMLTV 频道 id，来自 M3U `tvg-id`。节目单是按 XMLTV id/显示名建键的，
     * 用它匹配比拿台标 URL 去查可靠；空表示源未提供。
     */
    val tvgId: String = "",
)

data class LiveChannelLine(
    val name: String,
    val url: String,
    /**
     * 该线路自带的取流请求头（来自 M3U 的 `#EXTVLCOPT:http-user-agent|http-referrer|cookie`）。
     * 为空时由播放器使用全局默认请求头。
     */
    val headers: Map<String, String> = emptyMap(),
) {
    /** 线路协议提示（hls / flv / rtmp …），供播放器选择取流方式。 */
    val protocolHint: String
        get() {
            val path = url.substringBefore('?').lowercase()
            return when {
                url.startsWith("rtmp", true) || url.startsWith("rtsp", true) -> "rtmp"
                path.endsWith(".m3u8") || url.contains(".m3u8?", true) -> "hls"
                path.endsWith(".flv") || url.contains(".flv?", true) -> "flv"
                else -> ""
            }
        }
}

data class PlatformLiveChannel(
    val number: Int,
    val id: String,
    val name: String,
    val site: String,
    val roomId: String,
    val group: String,
)

data class PlatformLiveSite(
    val id: String,
    val name: String,
    val description: String,
)

data class PlatformLiveParentCategory(
    val id: String,
    val name: String,
    val cover: String,
)

data class PlatformLiveCategory(
    val id: String,
    val name: String,
    val parentId: String,
    val parentName: String,
    val cover: String,
)

data class PlatformLiveRoom(
    val id: String,
    val site: String,
    val roomId: String,
    val title: String,
    val anchor: String,
    val cover: String,
    val online: Int,
    val categoryId: String,
    val categoryName: String,
)

data class PlatformLiveStreamCandidate(
    val cdn: String,
    val protocol: String,
    val url: String,
    /** 实测首字节延迟（毫秒）；-1 表示未测/测速失败，UI 不显示副标题。 */
    val latencyMs: Int = -1,
) {
    /** 线路列表的副标题：把延迟作为可选信息展示，避免占用主标题宽度。 */
    val latencyLabel: String?
        get() = when {
            latencyMs < 0 -> null
            latencyMs < 1000 -> "${latencyMs}ms"
            else -> "${latencyMs / 1000}.${(latencyMs % 1000) / 100}s"
        }
}

data class ResolvedPlatformLiveStream(
    val channelId: String,
    val title: String,
    val anchor: String,
    val quality: String,
    val headers: Map<String, String>,
    val candidates: List<PlatformLiveStreamCandidate>,
    val activeCandidateIndex: Int = 0,
) {
    val hasCandidates: Boolean
        get() = candidates.isNotEmpty()

    val safeActiveCandidateIndex: Int
        get() = if (candidates.isEmpty()) 0 else activeCandidateIndex.coerceIn(0, candidates.lastIndex)

    val activeCandidateOrNull: PlatformLiveStreamCandidate?
        get() = candidates.getOrNull(safeActiveCandidateIndex)

    val activeCandidate: PlatformLiveStreamCandidate
        get() = activeCandidateOrNull ?: PlatformLiveStreamCandidate(cdn = "默认", protocol = "", url = "")

    val protocol: String
        get() = activeCandidateOrNull?.protocol.orEmpty()

    val url: String
        get() = activeCandidateOrNull?.url.orEmpty()

    fun withActiveCandidate(index: Int): ResolvedPlatformLiveStream {
        if (candidates.isEmpty()) return copy(activeCandidateIndex = 0)
        return copy(activeCandidateIndex = index.coerceIn(0, candidates.lastIndex))
    }
}

/**
 * 把第三方代理解析候选插到官方解析候选之前：第三方在前按配置顺序互为备用，
 * 官方候选整体后移退为兜底。第三方代理候选标记 cdn=「第三方」，protocol=flv。
 *
 * 多平台：按 A 方案分组配置（`# 站点名` 分组）取该平台的模板，以房间号为主键展开。
 * 未启用、站点不受支持或该平台未配置有效模板时原样返回，绝不改动官方候选。
 *
 * 默认兜底（cfss.cc 主 + mursor 备用）仅对 huya/douyu 生效——内置默认模板 `{site}` 占位
 * 只在这两家有已知可用的第三方写法；其它平台必须由用户显式配置，避免凭空生成无效候选。
 */
fun ResolvedPlatformLiveStream.withThirdPartyProxyPrepended(
    site: String,
    roomId: String,
    enabled: Boolean,
    templatesRaw: String,
): ResolvedPlatformLiveStream {
    if (!enabled) return this
    val templates = ThirdPartyLiveProxy.templatesForSite(templatesRaw, site)
        .ifEmpty {
            // 该平台无分组配置时的兜底：仅 huya/douyu 用内置默认模板（向后兼容旧全局配置）。
            if (site.trim().lowercase() in setOf("huya", "douyu")) {
                ThirdPartyLiveProxy.defaultTemplates
            } else {
                emptyList()
            }
        }
    val thirdPartyUrls = ThirdPartyLiveProxy.candidateUrls(templates, site, roomId)
    if (thirdPartyUrls.isEmpty()) return this
    val officialUrls = candidates.map { it.url }.toSet()
    // 防御：若某个第三方地址与官方候选重复，不重复插入。
    val thirdPartyCandidates = thirdPartyUrls
        .filterNot { it in officialUrls }
        .map { url -> PlatformLiveStreamCandidate(cdn = "第三方", protocol = "flv", url = url) }
    if (thirdPartyCandidates.isEmpty()) return this
    return copy(candidates = thirdPartyCandidates + candidates)
}

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long,
    val force: Boolean,
    val changelog: List<String>,
    /**
     * 更新清单中**可选**的多 CDN 镜像地址（`apkMirrors`）。
     * 老版本 update.json 没有该字段时为空列表，客户端会从 apkUrl 派生 CDN 候选；
     * 既有 8 个字段的语义与结构保持不变。
     */
    val apkMirrors: List<String> = emptyList(),
    /**
     * 安装前要求的最低当前版本号（P39）；0 表示不限制。
     * 老版本 update.json 缺省为 0，新字段对老客户端透明（ignoreUnknownKeys）。
     */
    val minVersionCode: Long = 0L,
    /** 清单发布通道（P39）：`stable`（正式）或 `canary`（预览），缺省按正式通道处理。 */
    val channel: String = "stable",
    /**
     * S7-40：从 CHANGELOG「你会感觉到」抽出的 3 行人话，OTA 弹窗优先显示。
     * 老清单没有该字段时为空，UI 回退到 [changelog]。
     */
    val releaseHighlights: List<String> = emptyList(),
)
