package com.tvbox.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 仓库契约统一放在 domain 层：ui/ViewModel 只依赖这里的接口，data 层提供实现。
 *
 * 为保持既有 public API 不变，`data` 包中保留了同名的 typealias，
 * 现有 `import com.tvbox.app.data.XxxRepository` 仍然有效，无需改动调用点。
 */

/** 观看历史默认保留条数；可由 AppSettings.historyMaxItems 覆盖。 */
const val DEFAULT_HISTORY_MAX_ITEMS = 100

data class MultiSourceSearchUpdate(
    val movies: List<Movie>,
    val completedSources: Int,
    val totalSources: Int,
    /**
     * 每个来源的「原始」结果（跨源去重之前），键为 apiLineId。
     * TV 端继续用 [movies] 扁平去重列表；手机端据此按源分组展示，
     * 让同一部片在每个命中的源下都能看到。默认空表以兼容其它构造点。
     */
    val moviesBySource: Map<String, List<Movie>> = emptyMap(),
)

data class MovieDetailUpdate(
    val movie: Movie,
    val completedSources: Int,
    val totalSources: Int,
)

interface MovieRepository {
    val apiLines: List<ApiLine>
    val allApiLines: List<ApiLine>
    fun updateCustomApiLines(customApiLines: List<ApiLine>)
    fun setDisabledLineIds(disabledIds: Set<String>)
    /** 设置多源搜索/详情同时请求的源数量。 */
    fun setMaxParallelSourceRequests(value: Int) {}
    suspend fun testSpeed(apiLineId: String): Long?
    suspend fun getCategories(apiLineId: String): List<Category>
    suspend fun getMovies(apiLineId: String, page: Int, typeId: Int? = null, keyword: String? = null): PagedMovies
    suspend fun getMoviesByTypeIds(apiLineId: String, page: Int, typeIds: List<Int>): PagedMovies
    suspend fun getDetail(apiLineId: String, id: Int): Movie?
    suspend fun getResumeDetail(apiLineId: String, id: Int): Movie? = getDetail(apiLineId, id)

    /**
     * 探测该影片在「任一已启用源」中的已知最大集数，供历史跨源更新检查使用。
     *
     * 语义与 [getDetailProgressively] 不同：**不被主源（保存该记录的源）阻塞**，
     * 而是并发扇出到全部已启用源，取**最快成功返回**的那一条作为结果（先到先得），
     * 而不是等所有源 / 等主源先响。这样即使原记录源挂了或很慢，只要还有任意一个
     * 可用源能给出集数，就能正确判断「是否更新了」。返回 0 表示所有源都失败/未知。
     *
     * @param movieName 该影片的片名，来自历史记录；各源据它做同名匹配，
     *   避免探测结果依赖「主源必须先能按 id 取到详情才能拿到片名」这条阻塞链。
     */
    suspend fun probeLatestEpisodeCount(
        apiLineId: String,
        movieId: Int,
        movieName: String,
    ): Int = runCatching {
        getDetail(apiLineId = apiLineId, id = movieId)
    }.getOrNull()?.playSources?.maxOfOrNull { it.episodes.size } ?: 0

    fun searchProgressively(apiLineId: String, page: Int, keyword: String): Flow<MultiSourceSearchUpdate> = flow {
        val result = getMovies(apiLineId = apiLineId, page = page, keyword = keyword)
        emit(MultiSourceSearchUpdate(result.movies, completedSources = 1, totalSources = 1))
    }

    fun getDetailProgressively(apiLineId: String, id: Int): Flow<MovieDetailUpdate> = flow {
        getDetail(apiLineId = apiLineId, id = id)?.let { movie ->
            emit(MovieDetailUpdate(movie, completedSources = 1, totalSources = 1))
        }
    }
}

interface HistoryRepository {
    suspend fun getHistory(): List<WatchHistoryItem>
    suspend fun saveProgress(
        item: WatchHistoryItem,
        maxItems: Int = DEFAULT_HISTORY_MAX_ITEMS,
    ): List<WatchHistoryItem>
    /** 合并跨源更新检查结果：仅提升已知最大集数与检查时间，不改动播放进度。 */
    suspend fun updateEpisodeCheck(
        apiLineId: String,
        movieId: Int,
        latestEpisodeCount: Int,
        checkedAtEpochMs: Long = System.currentTimeMillis(),
    ): List<WatchHistoryItem> = getHistory()
    suspend fun clearHistory(): List<WatchHistoryItem>
    /** 删除单条观看记录（按来源 + 影片 id），返回删除后的最新列表。 */
    suspend fun removeHistoryItem(apiLineId: String, movieId: Int): List<WatchHistoryItem>
}

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
