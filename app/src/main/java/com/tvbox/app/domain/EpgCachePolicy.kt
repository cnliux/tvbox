package com.tvbox.app.domain

import androidx.compose.runtime.Immutable

/**
 * S3-19：EPG 节目单缓存的 stale-while-revalidate 策略（纯函数，JVM 单测可直接覆盖）。
 *
 * 背景：EPG 缓存过期后弱网重新拉取要 2~5s，原逻辑期间频道/节目名空白。改为：
 * - 缓存 [FRESH_TTL_MS] 内：FRESH，直接渲染；
 * - 超过 FRESH：STALE，旧数据照常渲染并在节目名上叠加「~」角标，同时后台静默重新拉取；
 * - 拉取成功后静默替换旧节目单（频道列表 LazyColumn 以频道号作 key，替换不跳焦点）；
 * - 拉取失败保留旧数据，角标继续显示，等待下次触发重验。
 * - 无任何缓存：LOADING，走既有加载/空态。
 *
 * FRESH 窗口取 6 小时：EPG 是每日滚动的节目单，节目单当天就会过期。原 7 天窗口与
 * 仓库层 30 分钟缓存语义倒挂——长驻进程会一次拉取后 7 天都不更新，节目单长期陈旧。
 * 6 小时既保证当天会刷新到新节目，又避免弱网（重拉 2~5s）过于频繁。
 */
object EpgCachePolicy {

    /** 新鲜缓存有效期：6 小时。 */
    const val FRESH_TTL_MS = 6L * 3_600_000L

    /** 纯函数：按缓存与当前时刻判定渲染态。 */
    fun renderState(cached: EpgData?, now: Long): EpgRenderState = when {
        cached == null || cached.programs.isEmpty() -> EpgRenderState.LOADING
        now - cached.fetchedAtMs < FRESH_TTL_MS -> EpgRenderState.FRESH
        else -> EpgRenderState.STALE
    }

    /** 是否需要（重新）拉取：无缓存或已过期（STALE/LOADING）都返回 true。 */
    fun shouldFetch(cached: EpgData?, now: Long): Boolean =
        renderState(cached, now) != EpgRenderState.FRESH
}

/** EPG 快照：节目单表 + 上次成功拉取的毫秒时间戳。 */
@Immutable
data class EpgData(
    val programs: Map<String, List<EpgProgram>>,
    val fetchedAtMs: Long,
)

/** EPG 渲染态：决定 UI 直接渲染、旧数据加「~」角标、还是走加载态。 */
enum class EpgRenderState(val showWithTilde: Boolean = false) {
    /** 缓存有效（FRESH 窗口内）：直接渲染。 */
    FRESH,

    /** 缓存过期：先渲染旧数据 + `~` 角标（[showWithTilde]），同时后台静默重新拉取。 */
    STALE(showWithTilde = true),

    /** 无缓存：加载中。 */
    LOADING,
}
