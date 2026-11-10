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
)

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
)
