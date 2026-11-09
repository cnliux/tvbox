package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.PlatformLiveChannel
import com.tvbox.app.domain.PlatformLiveCategory
import com.tvbox.app.domain.PlatformLiveParentCategory
import com.tvbox.app.domain.PlatformLiveRoom
import com.tvbox.app.domain.PlatformLiveSite
import com.tvbox.app.domain.PlatformLiveStreamCandidate
import com.tvbox.app.domain.ResolvedPlatformLiveStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

interface PlatformLiveRepository {
    suspend fun getChannels(): List<PlatformLiveChannel>
    suspend fun getSites(): List<PlatformLiveSite>
    suspend fun getCategoryTree(site: String): PlatformLiveCategoryTree
    suspend fun getRooms(site: String, categoryId: String, page: Int): PlatformLiveRoomsPage
    suspend fun resolve(room: PlatformLiveRoom, forceRefresh: Boolean = false): ResolvedPlatformLiveStream
}

data class PlatformLiveRoomsPage(
    val page: Int,
    val pageCount: Int,
    val rooms: List<PlatformLiveRoom>,
)

data class PlatformLiveCategoryTree(
    val parentCategories: List<PlatformLiveParentCategory>,
    val categories: List<PlatformLiveCategory>,
)

/**
 * APK 内置的平台直播解析器。
 *
 * 解析逻辑由 Go 实现（platform_live_go），通过 gomobile bind 产出的
 * `libs/platformlive.aar` 提供，不再依赖外部 platform_live_server 服务。
 * Go 侧接口返回的 JSON 结构与旧版 Python 桥接一致。
 */
class EmbeddedPlatformLiveRepository(
    context: Context? = null,
) : PlatformLiveRepository {
    private val appContext = context?.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun getChannels(): List<PlatformLiveChannel> = withContext(Dispatchers.IO) {
        val body = callBridge { platformlive.Platformlive.channelsJson() }
        val response = runCatching { json.decodeFromString<PlatformLiveCatalogResponse>(body) }
            .getOrElse { throw IOException("平台直播频道目录格式错误", it) }
        response.channels
            .mapNotNull { it.toDomainOrNull() }
            .distinctBy { it.id }
            .mapIndexed { index, channel -> channel.copy(number = index + 1) }
    }

    override suspend fun getSites(): List<PlatformLiveSite> = withContext(Dispatchers.IO) {
        val body = callBridge { platformlive.Platformlive.sitesJson() }
        val response = runCatching { json.decodeFromString<PlatformLiveSitesResponse>(body) }
            .getOrElse { throw IOException("平台直播平台列表格式错误", it) }
        response.sites.mapNotNull { it.toDomainOrNull() }
    }

    override suspend fun getCategoryTree(site: String): PlatformLiveCategoryTree = withContext(Dispatchers.IO) {
        val body = callBridge { platformlive.Platformlive.categoriesJson(site) }
        val response = runCatching { json.decodeFromString<PlatformLiveCategoriesResponse>(body) }
            .getOrElse { throw IOException("平台直播分类列表格式错误", it) }
        val categories = response.categories.mapNotNull { it.toDomainOrNull() }
        val parentCategories = response.parentCategories
            .mapNotNull { it.toDomainOrNull() }
            .ifEmpty {
                categories
                    .filter { it.parentId.isNotBlank() && it.parentName.isNotBlank() }
                    .distinctBy { it.parentId }
                    .map { category ->
                        PlatformLiveParentCategory(
                            id = category.parentId,
                            name = category.parentName,
                            cover = "",
                        )
                    }
            }
        PlatformLiveCategoryTree(
            parentCategories = parentCategories.distinctBy { it.id },
            categories = categories.distinctBy { it.id },
        )
    }

    override suspend fun getRooms(
        site: String,
        categoryId: String,
        page: Int,
    ): PlatformLiveRoomsPage = withContext(Dispatchers.IO) {
        val normalizedPage = page.coerceAtLeast(1)
        val body = callBridge { platformlive.Platformlive.roomsJson(site, categoryId, normalizedPage.toString()) }
        val response = runCatching { json.decodeFromString<PlatformLiveRoomsResponse>(body) }
            .getOrElse { throw IOException("平台直播房间列表格式错误", it) }
        PlatformLiveRoomsPage(
            page = response.page.coerceAtLeast(1),
            pageCount = response.pageCount.coerceAtLeast(1),
            rooms = response.rooms.mapNotNull { it.toDomainOrNull(site) },
        )
    }

    override suspend fun resolve(
        room: PlatformLiveRoom,
        forceRefresh: Boolean,
    ): ResolvedPlatformLiveStream = withContext(Dispatchers.IO) {
        val body = callBridge {
            platformlive.Platformlive.resolveJson(room.site, room.roomId, if (forceRefresh) "1" else "0")
        }
        val response = runCatching { json.decodeFromString<PlatformLiveResolveResponse>(body) }
            .getOrElse { throw IOException("平台直播解析结果格式错误", it) }
        if (!response.live) throw IOException("该房间当前未开播")
        val candidates = response.streams
            .mapNotNull { it.toDomainOrNull(response.protocol) }
            .ifEmpty {
                listOfNotNull(
                    PlatformLiveStreamCandidateDto(
                        cdn = "默认",
                        protocol = response.protocol,
                        url = response.url,
                    ).toDomainOrNull(response.protocol),
                )
            }
        if (candidates.isEmpty()) {
            throw IOException("解析服务未返回有效播放地址")
        }
        ResolvedPlatformLiveStream(
            channelId = room.id,
            title = response.title.trim().ifBlank { room.title },
            anchor = response.anchor.trim(),
            quality = response.quality.trim().ifBlank { "自动" },
            headers = response.headers
                .mapNotNull { (key, value) ->
                    val normalizedKey = key.trim()
                    val normalizedValue = value.trim()
                    normalizedKey.takeIf { it.isNotBlank() }?.let { it to normalizedValue }
                }
                .toMap(),
            candidates = candidates,
        )
    }

    /**
     * 调用 Go 桥接（gomobile bind 的 platformlive 包）。
     *
     * 错误包装保持与旧版 Python 桥接一致的语义：解析异常取最后一行描述，
     * 便于 UI 直接展示"斗鱼取流失败..." 等具体原因。
     */
    private fun callBridge(block: () -> String): String {
        ensureBridgeStarted()
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw IOException(bridgeErrorMessage(error), error)
        }
    }

    private fun ensureBridgeStarted() {
        PlatformLiveGoBridge.start(appContext)
    }

    private fun bridgeErrorMessage(error: Throwable): String =
        error.message?.trim().orEmpty().ifBlank { "直播解析失败" }
}

@Serializable
private data class PlatformLiveCatalogResponse(
    val channels: List<PlatformLiveChannelDto> = emptyList(),
)

@Serializable
private data class PlatformLiveSitesResponse(
    val sites: List<PlatformLiveSiteDto> = emptyList(),
)

@Serializable
private data class PlatformLiveSiteDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
) {
    fun toDomainOrNull(): PlatformLiveSite? {
        val normalizedId = id.trim().lowercase()
        val normalizedName = name.trim()
        if (normalizedId.isBlank() || normalizedName.isBlank()) return null
        return PlatformLiveSite(
            id = normalizedId,
            name = normalizedName,
            description = description.trim(),
        )
    }
}

@Serializable
private data class PlatformLiveCategoriesResponse(
    val parentCategories: List<PlatformLiveParentCategoryDto> = emptyList(),
    val categories: List<PlatformLiveCategoryDto> = emptyList(),
)

@Serializable
private data class PlatformLiveParentCategoryDto(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
) {
    fun toDomainOrNull(): PlatformLiveParentCategory? {
        val normalizedId = id.trim()
        val normalizedName = name.trim()
        if (normalizedId.isBlank() || normalizedName.isBlank()) return null
        return PlatformLiveParentCategory(
            id = normalizedId,
            name = normalizedName,
            cover = cover.trim(),
        )
    }
}

@Serializable
private data class PlatformLiveCategoryDto(
    val id: String = "",
    val name: String = "",
    val parentId: String = "",
    val parentName: String = "",
    val cover: String = "",
) {
    fun toDomainOrNull(): PlatformLiveCategory? {
        val normalizedId = id.trim()
        val normalizedName = name.trim()
        if (normalizedId.isBlank() || normalizedName.isBlank()) return null
        return PlatformLiveCategory(
            id = normalizedId,
            name = normalizedName,
            parentId = parentId.trim(),
            parentName = parentName.trim(),
            cover = cover.trim(),
        )
    }
}

@Serializable
private data class PlatformLiveRoomsResponse(
    val page: Int = 1,
    val pageCount: Int = 1,
    val rooms: List<PlatformLiveRoomDto> = emptyList(),
)

@Serializable
private data class PlatformLiveRoomDto(
    val roomId: String = "",
    val title: String = "",
    val anchor: String = "",
    val cover: String = "",
    val online: Int = 0,
    val categoryId: String = "",
    val categoryName: String = "",
) {
    fun toDomainOrNull(site: String): PlatformLiveRoom? {
        val normalizedRoomId = roomId.trim()
        if (normalizedRoomId.isBlank()) return null
        return PlatformLiveRoom(
            id = "${site.lowercase()}:$normalizedRoomId",
            site = site.lowercase(),
            roomId = normalizedRoomId,
            title = title.trim().ifBlank { "直播 $normalizedRoomId" },
            anchor = anchor.trim(),
            cover = cover.trim(),
            online = online.coerceAtLeast(0),
            categoryId = categoryId.trim(),
            categoryName = categoryName.trim(),
        )
    }
}

@Serializable
private data class PlatformLiveChannelDto(
    val id: String = "",
    val name: String = "",
    val site: String = "",
    val roomId: String = "",
    val group: String = "默认",
) {
    fun toDomainOrNull(): PlatformLiveChannel? {
        val normalizedId = id.trim()
        val normalizedName = name.trim()
        val normalizedSite = site.trim().lowercase()
        val normalizedRoomId = roomId.trim()
        if (normalizedId.isBlank() || normalizedName.isBlank() || normalizedSite.isBlank() ||
            normalizedRoomId.isBlank()
        ) {
            return null
        }
        return PlatformLiveChannel(
            number = 0,
            id = normalizedId,
            name = normalizedName,
            site = normalizedSite,
            roomId = normalizedRoomId,
            group = group.trim().ifBlank { "默认" },
        )
    }
}

@Serializable
private data class PlatformLiveResolveResponse(
    val live: Boolean = false,
    val title: String = "",
    val anchor: String = "",
    val quality: String = "",
    val protocol: String = "",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val streams: List<PlatformLiveStreamCandidateDto> = emptyList(),
)

@Serializable
private data class PlatformLiveStreamCandidateDto(
    val cdn: String = "",
    val protocol: String = "",
    val url: String = "",
) {
    fun toDomainOrNull(fallbackProtocol: String): PlatformLiveStreamCandidate? {
        val normalizedUrl = url.trim()
        if (!normalizedUrl.startsWith("https://") && !normalizedUrl.startsWith("http://")) {
            return null
        }
        return PlatformLiveStreamCandidate(
            cdn = cdn.trim().ifBlank { "默认" },
            protocol = protocol.trim().ifBlank { fallbackProtocol.trim().ifBlank { "flv" } },
            url = normalizedUrl,
        )
    }
}
