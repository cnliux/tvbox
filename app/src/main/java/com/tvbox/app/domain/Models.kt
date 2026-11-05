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
    val activeCandidate: PlatformLiveStreamCandidate
        get() = candidates[activeCandidateIndex.coerceIn(0, candidates.lastIndex)]

    val protocol: String
        get() = activeCandidate.protocol

    val url: String
        get() = activeCandidate.url

    fun withActiveCandidate(index: Int): ResolvedPlatformLiveStream = copy(
        activeCandidateIndex = index.coerceIn(0, candidates.lastIndex),
    )
}

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long,
    val force: Boolean,
    val changelog: List<String>,
)
