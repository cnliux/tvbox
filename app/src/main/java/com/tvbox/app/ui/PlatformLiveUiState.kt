package com.tvbox.app.ui

import com.tvbox.app.domain.PlatformLiveCategory
import com.tvbox.app.domain.PlatformLiveParentCategory
import com.tvbox.app.domain.PlatformLiveRoom
import com.tvbox.app.domain.PlatformLiveSite
import com.tvbox.app.domain.ResolvedPlatformLiveStream

/**
 * 平台直播 UI 状态（从 [TvBoxUiState] 拆出，字段语义与原 `platformLive*` 保持一致）。
 *
 * - [destination]：当前停留的页面层级（站点/一级分类/二级分类/房间/播放器/收藏）。
 * - [parentIndex]/[categoryIndex]/[roomIndex]：各列表上次选中下标，用于返回时恢复滚动与焦点。
 * - [notice]：轻提示（自动切换线路结果等），由 UI 消费后清空。
 */
data class PlatformLiveUiState(
    val destination: PlatformLiveDestination = PlatformLiveDestination.Sites,
    val sites: List<PlatformLiveSite> = emptyList(),
    /** 平台直播站点全量列表（含已删除的），供源管理页恢复使用。 */
    val allSites: List<PlatformLiveSite> = emptyList(),
    val quickCategories: List<PlatformLiveQuickCategory> = emptyList(),
    val favorites: List<PlatformLiveRoom> = emptyList(),
    val favoriteError: String? = null,
    val playerReturnDestination: PlatformLiveDestination = PlatformLiveDestination.Rooms,
    val selectedSite: PlatformLiveSite? = null,
    val parentCategories: List<PlatformLiveParentCategory> = emptyList(),
    val selectedParentCategory: PlatformLiveParentCategory? = null,
    /** 一级分类上次选中的下标：返回一级分类页时用于恢复滚动位置与焦点。 */
    val parentIndex: Int = 0,
    val categories: List<PlatformLiveCategory> = emptyList(),
    val selectedCategory: PlatformLiveCategory? = null,
    /** 二级分类上次选中的下标：返回二级分类页时用于恢复滚动位置与焦点。 */
    val categoryIndex: Int = 0,
    val rooms: List<PlatformLiveRoom> = emptyList(),
    val roomIndex: Int = 0,
    val roomPage: Int = 1,
    val roomPageCount: Int = 1,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val resolving: Boolean = false,
    val error: String? = null,
    /** 平台直播轻提示（自动切换线路结果等）；由 UI 消费后清空。 */
    val notice: String? = null,
    val stream: ResolvedPlatformLiveStream? = null,
)
