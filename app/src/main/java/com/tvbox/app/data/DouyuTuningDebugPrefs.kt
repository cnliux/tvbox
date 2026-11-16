package com.tvbox.app.data

import android.content.Context
import platformlive.Platformlive

/**
 * 斗鱼「一起看」续流/重连参数的调试持久化（配合 Go 侧 flv_proxy_douyu_tuning.go）。
 *
 * 场景：斗鱼匿名流带租约，CDN 到点断流，代理在到期前主动重解析换签名。若
 * 「提前量 / 空闲超时 / 短段退避」与当前 CDN 行为不匹配，会表现为「总是重连」。
 * 这些参数原先写死在 Go 常量里，无法真机调整。这里提供一套「策略档位」，
 * 一键循环切换并即时写回 Go，方便现场定位最优组合，无需重新发版。
 *
 * 档位定义（三档互斥，越保守越不容易触发可见重连）：
 * - 稳健(0)：提前量 60s、空闲超时 30s、短段退避 3s→12s。最不容易误重连，
 *   代价是租约续期更频繁、卡顿恢复更慢。
 * - 默认(1)：提前量 45s、空闲 15s、退避 2s→8s。与历史常量一致。
 * - 耐心(2)：提前量 30s、空闲 25s、退避 4s→16s。续期更晚、对抖动更宽容，
 *   适合网络波动大、短段多的环境。
 *
 * 仅影响斗鱼；虎牙/其它平台参数不变。默认档位 = 默认(1)，即历史行为。
 */
object DouyuTuningDebugPrefs {
    private const val PREF_NAME = "tvbox.live.debug"
    private const val KEY_PROFILE = "douyu_reconnect_profile"
    private const val DEFAULT_PROFILE = 1

    data class Profile(
        val label: String,
        val leaseLeadMs: Long,
        val idleTimeoutMs: Long,
        val shortBackoffBaseMs: Long,
        val shortBackoffMaxMs: Long,
    )

    /** 循环顺序即数组顺序：稳健 → 默认 → 耐心 → 稳健… */
    val PROFILES = listOf(
        Profile("稳健", 60_000L, 30_000L, 3_000L, 12_000L),
        Profile("默认", 45_000L, 15_000L, 2_000L, 8_000L),
        Profile("耐心", 30_000L, 25_000L, 4_000L, 16_000L),
    )

    fun loadProfileIndex(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val idx = prefs.getInt(KEY_PROFILE, DEFAULT_PROFILE)
        return if (idx in PROFILES.indices) idx else DEFAULT_PROFILE
    }

    fun currentProfile(context: Context): Profile = PROFILES[loadProfileIndex(context)]

    fun saveProfileIndex(context: Context, index: Int) {
        if (index !in PROFILES.indices) return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PROFILE, index)
            .apply()
    }

    fun nextProfileIndex(current: Int): Int = (current + 1) % PROFILES.size

    /** 把指定档位的全部参数写回 Go；应在 Platformlive.init 之后调用。 */
    fun applyToBridge(context: Context) {
        val p = currentProfile(context)
        runCatching {
            Platformlive.setDouyuLeaseRefreshLeadMs(p.leaseLeadMs)
            Platformlive.setDouyuIdleReadTimeoutMs(p.idleTimeoutMs)
            Platformlive.setDouyuShortBackoffBaseMs(p.shortBackoffBaseMs)
            Platformlive.setDouyuShortBackoffMaxMs(p.shortBackoffMaxMs)
        }
    }

    /** 供调试叠加层显示当前生效档位摘要。 */
    fun summary(context: Context): String {
        val p = currentProfile(context)
        return "${p.label}(提前${p.leaseLeadMs / 1000}s/空闲${p.idleTimeoutMs / 1000}s/退避${p.shortBackoffBaseMs / 1000}s)"
    }
}
