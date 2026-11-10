package com.tvbox.app.domain

/**
 * 首页信息流的细粒度分页状态（第 79 轮）。
 *
 * 现状：`TvBoxUiState` 把 page / pageCount / movies / homeLoading / loadingMore / homeError
 * 全放在同一个大 State 里，任一字段变化都会让整棵首页子树重组；分页判定又散在
 * `loadDoubanHotPage` 与 `launchCatalogHomePage` 两处。
 *
 * 这里把「下一页该不该请求、要不要清空」抽成不依赖 Android 的纯判定，
 * 供后续 state holder 与 useCase（第 101 轮）复用，UI 不受影响。
 */
data class HomeFeedPageState(
    val page: Int = 1,
    /** <= 0 表示服务端未给出总页数，此时允许继续试探下一页。 */
    val pageCount: Int = 0,
    val itemCount: Int = 0,
)

data class HomeFeedPageRequest(
    val nextPage: Int,
    val clearExisting: Boolean,
)

/**
 * @param reset 下拉刷新 / 换分类 / 换源，回到第 1 页并清空已有列表
 * @param explicitPage 指定页（如重试上一次失败的加载更多），优先于 page+1
 * @return null 表示没有下一页，调用方不应再发请求
 */
fun resolveHomeFeedPageRequest(
    state: HomeFeedPageState,
    reset: Boolean,
    explicitPage: Int? = null,
): HomeFeedPageRequest? {
    if (reset) return HomeFeedPageRequest(nextPage = 1, clearExisting = true)
    val target = explicitPage ?: (state.page + 1)
    if (target < 1) return null
    if (state.pageCount > 0 && target > state.pageCount) return null
    return HomeFeedPageRequest(nextPage = target, clearExisting = false)
}

/** 是否还能加载更多；pageCount 未知时保持可加载，避免服务端不返回总页数就锁死列表。 */
fun canLoadMoreHomeFeed(state: HomeFeedPageState): Boolean =
    state.pageCount <= 0 || state.page < state.pageCount
