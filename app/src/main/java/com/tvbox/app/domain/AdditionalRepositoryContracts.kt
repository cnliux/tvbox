package com.tvbox.app.domain

/**
 * 第二批迁到 domain 的仓库契约。
 *
 * 与 `RepositoryContracts.kt` 一样：domain 只放接口与接口签名直接引用的模型，
 * 网络 / 缓存 / 序列化实现留在 data 层，data 包用同名 typealias 保持既有 import 不变。
 */

enum class DoubanHotCategory(
    val category: String,
    val type: String,
) {
    Tv(category = "tv", type = "tv"),
}

interface DoubanHotRepository {
    suspend fun getRecentHot(
        page: Int,
        forceRefresh: Boolean = false,
        category: DoubanHotCategory = DoubanHotCategory.Tv,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): PagedDoubanHotMovies

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

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
