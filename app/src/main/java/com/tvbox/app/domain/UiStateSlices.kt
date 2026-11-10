package com.tvbox.app.domain

/**
 * ViewModel 超大 State 的细粒度切片（第 100/101/103/104/141/142/144/145 轮）。
 * 首页 / 搜索 / 直播各自只持有自己需要的字段，派生值由纯函数计算，
 * 对应 Compose 侧用 derivedStateOf 包一层即可避免整棵子树重组。
 */

data class HomeFeedSlice(
    val page: Int = 1,
    val pageCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val itemCount: Int = 0,
)

data class SearchSlice(
    val keyword: String = "",
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val isLoading: Boolean = false,
    val resultCount: Int = 0,
)

data class LiveSlice(
    val channelNumber: Int = 0,
    val lineIndex: Int = 0,
    val isBuffering: Boolean = false,
    val channelListVisible: Boolean = false,
)

/** 从分页状态派生首页切片；分页判定复用第 79 轮的纯函数，避免两处逻辑漂移。 */
fun deriveHomeFeedSlice(
    paging: HomeFeedPageState,
    isLoading: Boolean,
    error: String?,
): HomeFeedSlice = HomeFeedSlice(
    page = paging.page,
    pageCount = paging.pageCount,
    isLoading = isLoading,
    error = error,
    itemCount = paging.itemCount,
)

fun deriveHomeFeedPageRequest(slice: HomeFeedSlice, reset: Boolean): HomeFeedPageRequest? =
    resolveHomeFeedPageRequest(
        HomeFeedPageState(slice.page, slice.pageCount, slice.itemCount),
        reset = reset,
    )

/** 搜索进度文案：来源数为 0 时不显示「0/0」，避免闪烁。 */
fun deriveSearchProgress(slice: SearchSlice): String = when {
    slice.totalSources <= 0 -> ""
    slice.isLoading -> "已检索 ${slice.completedSources}/${slice.totalSources} 个来源"
    else -> "共 ${slice.resultCount} 条结果"
}

/** 按来源权重拆分单源超时预算，供 fan-out 工具的 [timeoutForSource] 使用。 */
fun splitSearchTimeoutBudget(weights: List<Int>, baseTimeoutMs: Long): List<Long> =
    weightedTimeouts(weights, baseTimeoutMs)

/** useCase 编排入口的轻量封装：把首页取数与屏蔽过滤统一到 domain 层。 */
suspend fun loadHomeFeedSlice(
    useCase: GetHomeFeedUseCase,
    apiLineId: String,
    request: HomeFeedPageRequest,
    typeIds: List<Int> = emptyList(),
): Pair<HomeFeedSlice, Int> {
    val paged = useCase(apiLineId, request.nextPage, typeIds)
    val slice = HomeFeedSlice(
        page = request.nextPage,
        pageCount = paged.pageCount,
        itemCount = paged.movies.size,
    )
    return slice to paged.movies.size
}
