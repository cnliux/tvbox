package com.tvbox.app.domain

import com.tvbox.app.domain.UpdateChannelPolicy.ChannelDecision
import com.tvbox.app.domain.UpdateChannelPolicy.UpdateChannel
import com.tvbox.app.domain.UpdateChannelPolicy.UpdateManifestChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P39：UpdateChannelPolicy 单测。覆盖通道升级 a-e 五条规则与边界条件。
 * 纯函数测试，无 Android 依赖。
 */
class UpdateChannelPolicyTest {

    private fun manifest(
        versionCode: Int,
        channel: UpdateChannel = UpdateChannel.Stable,
        minVersionCode: Int = 0,
    ) = UpdateManifestChannel(
        versionName = "v$versionCode",
        versionCode = versionCode,
        channel = channel,
        minVersionCode = minVersionCode,
        downloadUrl = "https://example.com/JuheVideo-$versionCode.apk",
        sha256 = "a".repeat(64),
    )

    private fun decide(
        current: Int,
        target: UpdateManifestChannel,
        installed: UpdateChannel,
    ): ChannelDecision = UpdateChannelPolicy.shouldOffer(
        currentVersionCode = current,
        manifest = target,
        installedChannel = installed,
    )

    // 边界 1：current == manifest.versionCode → 不 offer（已是最新）。
    @Test
    fun sameVersionCodeDoesNotOffer() {
        val decision = decide(100, manifest(versionCode = 100), UpdateChannel.Stable)
        assertFalse(decision.offer)
        assertTrue(decision.reason.contains("最新"))
    }

    // 边界 2：current > manifest.versionCode → 不 offer（降级拒绝）。
    @Test
    fun olderManifestIsRejectedAsDowngrade() {
        val decision = decide(120, manifest(versionCode = 100), UpdateChannel.Stable)
        assertFalse(decision.offer)
        assertTrue(decision.reason.contains("降级"))
    }

    // 规则 b：canary 清单 + stable 安装 → 不 offer。
    @Test
    fun canaryManifestDoesNotOverrideStableInstall() {
        val decision = decide(100, manifest(versionCode = 200, channel = UpdateChannel.Canary), UpdateChannel.Stable)
        assertFalse(decision.offer)
        assertTrue(decision.reason.contains("canary"))
    }

    // 规则 c：stable 清单 + canary 安装 → offer（回正式）。
    @Test
    fun stableManifestOffersRollbackToCanaryInstall() {
        val decision = decide(100, manifest(versionCode = 200, channel = UpdateChannel.Stable), UpdateChannel.Canary)
        assertTrue(decision.offer)
        assertTrue(decision.reason.contains("正式"))
    }

    // 规则 d：同 channel + 更高 versionCode → offer。
    @Test
    fun sameChannelHigherVersionOffers() {
        val stableToStable = decide(100, manifest(versionCode = 200), UpdateChannel.Stable)
        assertTrue(stableToStable.offer)
        val canaryToCanary = decide(
            100,
            manifest(versionCode = 200, channel = UpdateChannel.Canary),
            UpdateChannel.Canary,
        )
        assertTrue(canaryToCanary.offer)
    }

    // 规则 e：current < minVersionCode（即使清单 versionCode 未明显领先）→ 强制 offer，reason 含「必须」。
    @Test
    fun belowMinVersionCodeForcesOfferEvenWithoutNewerManifest() {
        // 退化案例：清单 versionCode == minVersionCode == 60，current=50 低于下限。
        // 规则 1 优先于规则 2，因此判定为「必须升级」而非「已是最新」。
        val decision = decide(50, manifest(versionCode = 60, minVersionCode = 60), UpdateChannel.Stable)
        assertTrue(decision.offer)
        assertTrue(decision.reason.contains("必须"))
    }

    @Test
    fun belowMinVersionCodeForcesOfferAcrossChannels() {
        // canary 安装 + canary 清单：通道一致，minVersionCode 触发强制语义。
        val decision = decide(
            30,
            manifest(versionCode = 40, channel = UpdateChannel.Canary, minVersionCode = 35),
            UpdateChannel.Canary,
        )
        assertTrue(decision.offer)
        assertTrue(decision.reason.contains("必须"))
    }

    // 边界：minVersionCode 默认 0 → 不触发强制。
    @Test
    fun defaultMinVersionCodeDoesNotForce() {
        val decision = decide(200, manifest(versionCode = 100), UpdateChannel.Stable)
        assertFalse(decision.offer)
        assertFalse(decision.reason.contains("必须"))
    }

    @Test
    fun minVersionCodeEqualToCurrentDoesNotForce() {
        // minVersionCode == current 不算「低于下限」，按常规规则走（清单更高，offer 但原因不是强制）。
        val decision = decide(100, manifest(versionCode = 200, minVersionCode = 100), UpdateChannel.Stable)
        assertTrue(decision.offer)
        assertFalse(decision.reason.contains("必须"))
    }

    // 边界：同 channel + 相同 versionCode → 不 offer。
    @Test
    fun sameChannelSameVersionDoesNotOffer() {
        val decision = decide(
            300,
            manifest(versionCode = 300, channel = UpdateChannel.Canary),
            UpdateChannel.Canary,
        )
        assertFalse(decision.offer)
    }

    // 通道 id 解析：未知/大小写混杂字符串回落到 Stable（保守策略）。
    @Test
    fun channelIdParsingFallsBackToStable() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromId("stable"))
        assertEquals(UpdateChannel.Canary, UpdateChannel.fromId(" Canary "))
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromId("nightly"))
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromId(null))
    }

    // minVersionCode 合法性检查：越界视为清单配置错误，不触发强制。
    @Test
    fun insaneMinVersionCodeIsIgnored() {
        val beyondManifest = manifest(versionCode = 100, minVersionCode = 500)
        assertFalse(UpdateChannelPolicy.isMinVersionCodeSane(beyondManifest))
        // 安装 50 < minVersionCode=500，但配置非法 → 不应强制（按 versionCode 比较走：50<100 → 常规 offer）。
        val decision = decide(50, beyondManifest, UpdateChannel.Stable)
        assertTrue(decision.offer)
        assertFalse(decision.reason.contains("必须"))

        val negative = manifest(versionCode = 100, minVersionCode = -1)
        assertFalse(UpdateChannelPolicy.isMinVersionCodeSane(negative))
    }
}
