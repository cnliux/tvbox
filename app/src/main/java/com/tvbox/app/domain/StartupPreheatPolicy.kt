package com.tvbox.app.domain

/**
 * S5-30：启动预热目标决策纯函数。
 *
 * 背景：设置 / 豆瓣热榜 / 更新仓库的 `by lazy` 读盘代价不在 Application，而是落在
 * 首个访问者（ViewModel 工厂 → 首帧后 ~200ms 的首次触发）身上，阻塞 UI 线程。
 * 修复口径：[TvBoxApplication][com.tvbox.app.TvBoxApplication] 在 onCreate 里于
 * Dispatchers.IO 协程预热，让后续 `by lazy` 首个访问者直接拿到已预热的实例。
 *
 * 预热哪些目标由本策略按设备形态与内存压力决定，判定无 Android 依赖，可 JVM 单测：
 * - 低内存设备：只预热最常用的 [PREFS]（设置是启动关键路径），跳过其余避免预热自身加重内存压力；
 * - 电视（资源充裕）：三个目标全预热；
 * - 手机：预热 [PREFS] 与 [DOUBAN_HOT]（首页路径），[UPDATE_REPO] 交给按需 lazy。
 */
object StartupPreheatPolicy {

    /** 设置 prefs（迁移标记 SharedPreferences，getSettings 每次都会读）。 */
    const val PREFS = "prefsRegistry"

    /** 豆瓣热榜缓存（首页「豆瓣热播」路径）。 */
    const val DOUBAN_HOT = "doubanHot"

    /** 更新仓库（后台更新 / OTA CDN 健康度 prefs，更新路径才涉及）。 */
    const val UPDATE_REPO = "updateRepo"

    /** 决策预热目标，语义见对象头注释。 */
    fun shouldPreheat(isTv: Boolean, isLowMemory: Boolean): List<String> = when {
        isLowMemory -> listOf(PREFS)
        isTv -> listOf(PREFS, DOUBAN_HOT, UPDATE_REPO)
        else -> listOf(PREFS, DOUBAN_HOT)
    }
}
