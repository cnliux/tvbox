package com.tvbox.app.data

import android.content.Context
import platformlive.Platformlive

/**
 * 平台直播「一起看」续流/重连参数的调试持久化（配合 Go 侧 flv_proxy_*_tuning.go）。
 *
 * 斗鱼与虎牙断流机理不同，参数维度也不同：
 * - 斗鱼：URL 带租约（expire=N），CDN 到点断流，代理在到期前主动重解析换签名。
 *   维度 = 续期提前量 / 空闲超时 / 短段退避。
 * - 虎牙：一次性 anti-code 签名，CDN 对单连接约 120~137s 会话上限，代理预建双连接
 *   在关键帧无感交接。维度 = 预建提前量 / 标称会话 / 空闲超时 / 短段退避 / 换线阈值。
 *
 * 每站点三档策略互斥，一键循环切换并即时写回 Go，现场定位最优组合无需发版。
 * 默认档 = 历史行为。仅影响斗鱼/虎牙，其它平台不变。
 */
object LiveTuningDebugPrefs {
    private const val PREF_NAME = "tvbox.live.debug"

    enum class Site(val prefKey: String) {
        DOUYU("douyu_reconnect_profile"), // 复用 v1.0.15 旧 key，老用户选择不丢
        HUYA("huya_reconnect_profile"),
    }

    data class Profile(
        val label: String,
        // 斗鱼维度
        val leaseLeadMs: Long? = null,
        // 虎牙维度
        val plannedRefreshLeadMs: Long? = null,
        val nominalSessionMs: Long? = null,
        val maxShortStreak: Int? = null,
        // 两站共用维度
        val idleTimeoutMs: Long,
        val shortBackoffBaseMs: Long,
        val shortBackoffMaxMs: Long,
    )

    /** 斗鱼三档，逐字复用 v1.0.15（稳健/默认/耐心）。 */
    val DOUYU_PROFILES = listOf(
        Profile("稳健", leaseLeadMs = 60_000L, idleTimeoutMs = 30_000L, shortBackoffBaseMs = 3_000L, shortBackoffMaxMs = 12_000L),
        Profile("默认", leaseLeadMs = 45_000L, idleTimeoutMs = 15_000L, shortBackoffBaseMs = 2_000L, shortBackoffMaxMs = 8_000L),
        Profile("耐心", leaseLeadMs = 30_000L, idleTimeoutMs = 25_000L, shortBackoffBaseMs = 4_000L, shortBackoffMaxMs = 16_000L),
    )

    /**
     * 虎牙三档（无租约语义，维度换成预建/标称会话/换线阈值）。
     * 默认档除空闲读超时外保持历史常量（12s/125s/8s/0.5→5s/streak4）；
     * 空闲超时必须小于 Kotlin 12s 硬看门狗，否则代理来不及自愈就被整体重建。
     */
    val HUYA_PROFILES = listOf(
        // 稳健：更早预建、退避更大、少换线——最不容易出现可见重连。
        Profile("稳健", plannedRefreshLeadMs = 20_000L, nominalSessionMs = 125_000L, maxShortStreak = 2,
            idleTimeoutMs = 10_000L, shortBackoffBaseMs = 1_000L, shortBackoffMaxMs = 8_000L),
        // 默认：历史行为。
        Profile("默认", plannedRefreshLeadMs = 12_000L, nominalSessionMs = 125_000L, maxShortStreak = 4,
            idleTimeoutMs = 8_000L, shortBackoffBaseMs = 500L, shortBackoffMaxMs = 5_000L),
        // 耐心：预建更晚、退避更大、更愿意换线找好线路，适合网络抖动大环境。
        Profile("耐心", plannedRefreshLeadMs = 8_000L, nominalSessionMs = 130_000L, maxShortStreak = 6,
            idleTimeoutMs = 9_000L, shortBackoffBaseMs = 2_000L, shortBackoffMaxMs = 12_000L),
    )

    /** 支持现场调参的站点集合（UI 按钮显示条件）。 */
    val SUPPORTED_SITES = setOf("douyu", "huya")

    fun siteOf(raw: String?): Site? = when (raw?.trim()?.lowercase()) {
        "douyu" -> Site.DOUYU
        "huya" -> Site.HUYA
        else -> null
    }

    fun profiles(site: Site): List<Profile> = when (site) {
        Site.DOUYU -> DOUYU_PROFILES
        Site.HUYA -> HUYA_PROFILES
    }

    fun loadProfileIndex(context: Context, site: Site): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val idx = prefs.getInt(site.prefKey, 1) // 默认档=索引1
        val list = profiles(site)
        return if (idx in list.indices) idx else 1
    }

    fun currentProfile(context: Context, site: Site): Profile = profiles(site)[loadProfileIndex(context, site)]

    fun saveProfileIndex(context: Context, site: Site, index: Int) {
        if (index !in profiles(site).indices) return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(site.prefKey, index)
            .apply()
    }

    fun nextProfileIndex(site: Site, current: Int): Int = (current + 1) % profiles(site).size

    // ── 虎牙码率上限（弱设备自动降档） ──────────────────────────────
    //
    // 真机（p230）实测：蓝光 8M 1080p60 下 Amlogic OMX AVC 解码器只能渲出 ~34fps，
    // 丢帧率 40%+，表现为卡顿 + 画面落后声音的音画不同步。播放器侧看门狗检测到
    // 持续高丢帧后按 8000→2000→550 阶梯降上限并重解析；0 = 不设上限（默认）。

    /** 降档阶梯：由高到低的码率上限（对应虎牙 iBitRate：蓝光/超清/高清）。 */
    val HUYA_BITRATE_LADDER = listOf(8000L, 2000L, 550L)

    /** 码率上限对应的清晰度名（提示用）。 */
    fun huyaBitRateLabel(bitRate: Long): String = when (bitRate) {
        8000L -> "蓝光"
        2000L -> "超清"
        550L -> "高清"
        else -> "${bitRate}Kbps"
    }

    /** 读取虎牙码率上限偏好；0 = 不设上限。 */
    fun loadHuyaMaxBitRate(context: Context): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(HUYA_MAX_BITRATE_KEY, 0L)

    /** 保存虎牙码率上限偏好（不写回 Go，由 [applyToBridge] 统一写回）。 */
    fun saveHuyaMaxBitRate(context: Context, bitRate: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(HUYA_MAX_BITRATE_KEY, bitRate)
            .apply()
    }

    private const val HUYA_MAX_BITRATE_KEY = "huya_max_bit_rate"

    /** init 之后调用一次，写回双站点全部参数。 */
    fun applyToBridge(context: Context) {
        applyToBridge(context, Site.DOUYU)
        applyToBridge(context, Site.HUYA)
    }

    /** 按钮点击时调用，只写回单站点参数（对在播会话即时生效）。 */
    fun applyToBridge(context: Context, site: Site) {
        val p = currentProfile(context, site)
        runCatching {
            when (site) {
                Site.DOUYU -> {
                    Platformlive.setDouyuLeaseRefreshLeadMs(p.leaseLeadMs ?: 45_000L)
                    Platformlive.setDouyuIdleReadTimeoutMs(p.idleTimeoutMs)
                    Platformlive.setDouyuShortBackoffBaseMs(p.shortBackoffBaseMs)
                    Platformlive.setDouyuShortBackoffMaxMs(p.shortBackoffMaxMs)
                }
                Site.HUYA -> {
                    Platformlive.setHuyaPlannedRefreshLeadMs(p.plannedRefreshLeadMs ?: 12_000L)
                    Platformlive.setHuyaNominalSessionMs(p.nominalSessionMs ?: 125_000L)
                    Platformlive.setHuyaIdleReadTimeoutMs(p.idleTimeoutMs)
                    Platformlive.setHuyaShortBackoffBaseMs(p.shortBackoffBaseMs)
                    Platformlive.setHuyaShortBackoffMaxMs(p.shortBackoffMaxMs)
                    Platformlive.setHuyaMaxShortStreak((p.maxShortStreak ?: 4).toLong())
                    // 码率上限（弱设备降档）；0 = 不设上限。
                    Platformlive.setHuyaMaxBitRate(loadHuyaMaxBitRate(context))
                }
            }
        }
    }

    /** 供调试叠加层显示当前生效档位摘要。 */
    fun summary(context: Context, site: Site): String {
        val p = currentProfile(context, site)
        return when (site) {
            Site.DOUYU -> "${p.label}(提前${p.leaseLeadMs?.div(1000)}s/空闲${p.idleTimeoutMs / 1000}s/退避${p.shortBackoffBaseMs / 1000}s)"
            Site.HUYA -> "${p.label}(预建${p.plannedRefreshLeadMs?.div(1000)}s/空闲${p.idleTimeoutMs / 1000}s/退避${p.shortBackoffBaseMs / 1000}s)"
        }
    }
}
