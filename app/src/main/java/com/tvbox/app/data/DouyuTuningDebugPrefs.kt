package com.tvbox.app.data

import android.content.Context

/**
 * 斗鱼重连调优的兼容门面（v1.0.16 起核心逻辑迁移到 [LiveTuningDebugPrefs]）。
 *
 * 保留此对象是为了不破坏 v1.0.15 的调用点；所有函数转发到双站点实现。
 * 新代码请直接使用 LiveTuningDebugPrefs，以同时支持斗鱼与虎牙。
 */
object DouyuTuningDebugPrefs {
    private val site = LiveTuningDebugPrefs.Site.DOUYU

    data class Profile(
        val label: String,
        val leaseLeadMs: Long,
        val idleTimeoutMs: Long,
        val shortBackoffBaseMs: Long,
        val shortBackoffMaxMs: Long,
    )

    val PROFILES: List<Profile> = LiveTuningDebugPrefs.DOUYU_PROFILES.map {
        Profile(it.label, it.leaseLeadMs ?: 45_000L, it.idleTimeoutMs, it.shortBackoffBaseMs, it.shortBackoffMaxMs)
    }

    fun loadProfileIndex(context: Context): Int = LiveTuningDebugPrefs.loadProfileIndex(context, site)

    fun currentProfile(context: Context): Profile = PROFILES[loadProfileIndex(context)]

    fun saveProfileIndex(context: Context, index: Int) = LiveTuningDebugPrefs.saveProfileIndex(context, site, index)

    fun nextProfileIndex(current: Int): Int = LiveTuningDebugPrefs.nextProfileIndex(site, current)

    fun applyToBridge(context: Context) = LiveTuningDebugPrefs.applyToBridge(context, site)

    fun summary(context: Context): String = LiveTuningDebugPrefs.summary(context, site)
}
