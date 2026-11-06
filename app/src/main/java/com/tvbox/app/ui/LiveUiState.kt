package com.tvbox.app.ui

import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.LiveChannel

/**
 * 电视直播 UI 状态（从 [TvBoxUiState] 拆出，字段语义与原 `live*` 前缀字段保持一致）。
 *
 * - [channels]/[channelIndex]/[lineIndex]/[groupIndex]：频道列表与当前播放目标（频道、线路、分组）。
 * - [selectedSourceIndex]：当前订阅地址下标（-1 表示合并全部订阅地址加载）。
 * - [epgByChannel]/[epgLoaded]/[epgBundledUrl]：节目单数据、加载标记与源自带地址。
 * - [favoriteChannelNumbers]/[favoriteKeys]：旧频道号收藏与持久化的 `分组#频道名` 收藏键。
 * - [lineLatencies]/[lineTesting]：当前频道各线路首包延迟与测速状态。
 */
data class LiveUiState(
    val channels: List<LiveChannel> = emptyList(),
    val channelIndex: Int = 0,
    val lineIndex: Int = 0,
    /** 直播分类在当前频道列表中的下标；0 表示“全部”，其余按下标过滤分组。 */
    val groupIndex: Int = 0,
    /** 当前选中的订阅地址下标（-1 表示使用全部订阅地址合并加载）。 */
    val selectedSourceIndex: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
    /** 直播页轻提示（自动切线路结果、订阅切换结果等）；由 UI 消费后清空。 */
    val notice: String? = null,
    /** 原地重连当前线路的触发计数（不改变 URL，仅让播放页重新 prepare）。 */
    val reconnectNonce: Int = 0,
    /** 直播面板两级层级：分类列表 / 频道列表。 */
    val groupPanelStage: LiveGroupStage = LiveGroupStage.Groups,
    val favoriteChannelNumbers: Set<Int> = emptySet(),
    /** 电视直播收藏键集合（`分组#频道名`），持久化于 LiveFavoritesRepository；旧频道号集合保留兼容。 */
    val favoriteKeys: Set<String> = emptySet(),
    /** XMLTV 节目单：key 为频道 id / display-name，value 按开始时间升序。 */
    val epgByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    /** 节目单是否已完成一次拉取（无论成败），避免反复重试慢源。 */
    val epgLoaded: Boolean = false,
    /** 当前生效的「源自带 EPG 地址」，用于换源时判断是否需要重新采用。 */
    val epgBundledUrl: String = "",
    /** 当前频道各线路的首包延迟（url → 毫秒）；null 值表示测速超时/不可达。 */
    val lineLatencies: Map<String, Long?> = emptyMap(),
    /** 线路测速是否进行中，供线路浮层展示"测速中"。 */
    val lineTesting: Boolean = false,
)
