package com.tvbox.app.domain

/**
 * P39：update.json 通道发布策略。
 *
 * 这里嵌套的 [UpdateChannelPolicy.UpdateChannel] 描述**清单自身的发布通道**（manifest 的
 * `channel` 字段），与 `com.tvbox.app.domain.UpdateChannel`（用户在设置里选择「拉取哪个清单
 * 路径」的 Stable/Beta/Close）语义不同。二者重名会触发 Kotlin 重声明错误，因此清单通道枚举
 * 嵌套在策略对象内，使用时写作 `UpdateChannelPolicy.UpdateChannel`（可按需 import 别名）。
 */
object UpdateChannelPolicy {

    /**
     * update.json 的发布通道。
     *
     * @param id 清单 `channel` 字段的字符串值。
     */
    enum class UpdateChannel(val id: String) {
        Stable("stable"),
        Canary("canary"),
        ;

        companion object {
            /** 解析清单里的 `channel` 字符串；无法识别时按正式通道处理（保守策略）。 */
            fun fromId(value: String?): UpdateChannel =
                entries.firstOrNull { it.id == value?.trim()?.lowercase() } ?: Stable
        }
    }

    /**
     * update.json 中用于通道决策的最小字段集合。
     *
     * 与 [AppUpdate] 保持兼容：`AppUpdate.channel` 缺省 `"stable"`、`minVersionCode` 缺省 `0`，
     * 老清单不需要新字段就能通过 `UpdateChannel.fromId(update.channel)` 参与决策。
     */
    data class UpdateManifestChannel(
        val versionName: String,
        val versionCode: Int,
        val channel: UpdateChannel = UpdateChannel.Stable,
        /** 低于此 versionCode 的当前安装必须升级（强制推送下限）；0 表示不启用强制。 */
        val minVersionCode: Int = 0,
        val downloadUrl: String,
        val sha256: String,
    )

    /** 通道决策结果：是否向用户呈现该更新，以及原因（用于日志/UI 提示）。 */
    data class ChannelDecision(
        val offer: Boolean,
        val reason: String,
    )

    /** minVersionCode 合法性：必须落在 `[0, versionCode]`，否则视为清单配置错误，忽略强制逻辑。 */
    fun isMinVersionCodeSane(manifest: UpdateManifestChannel): Boolean =
        manifest.minVersionCode in 0..manifest.versionCode

    /**
     * 通道升级决策（纯函数，无 Android 依赖，便于单测）。
     *
     * 规则（按优先级从高到低；清单约束 minVersionCode <= versionCode）：
     * 1. 当前 versionCode 低于清单 `minVersionCode` → 强制 offer（必须升级）。
     * 2. 当前 versionCode >= 清单 versionCode → 不 offer（已是最新 / 降级拒绝）。
     * 3. 清单为 canary 但当前安装为 stable → 不 offer（canary 不覆盖正式）。
     * 4. 清单为 stable 且当前安装为 canary → offer（canary 可回正式）。
     * 5. 通道一致且清单 versionCode 更高 → offer。
     *
     * 规则 1 优先于规则 2。常规升级下 minVersionCode < versionCode，强制时清单版本也更高；
     * 仅在 minVersionCode == versionCode 的退化情况下与规则 2 重叠，按「必须升级」处理。
     */
    fun shouldOffer(
        currentVersionCode: Int,
        manifest: UpdateManifestChannel,
        installedChannel: UpdateChannel,
    ): ChannelDecision {
        val minSane = isMinVersionCodeSane(manifest)
        // 规则 1：强制升级下限。仅在 minVersionCode 合法时生效，避免清单配置错误触发误强推。
        if (minSane && manifest.minVersionCode > 0 && currentVersionCode < manifest.minVersionCode) {
            return ChannelDecision(
                offer = true,
                reason = "当前版本 $currentVersionCode 低于强制升级下限 ${manifest.minVersionCode}，必须升级",
            )
        }
        // 规则 2：已是最新或降级安装一律拒绝。
        if (manifest.versionCode <= currentVersionCode) {
            val reason = if (manifest.versionCode == currentVersionCode) {
                "已是最新版本 $currentVersionCode，无需更新"
            } else {
                "清单版本 ${manifest.versionCode} 不高于当前版本 $currentVersionCode，拒绝降级安装"
            }
            return ChannelDecision(offer = false, reason = reason)
        }
        // 规则 3：canary 清单不覆盖 stable 安装。
        if (manifest.channel == UpdateChannel.Canary && installedChannel == UpdateChannel.Stable) {
            return ChannelDecision(
                offer = false,
                reason = "清单为 canary（${manifest.versionCode}），当前为 stable 通道，canary 不覆盖正式版",
            )
        }
        // 规则 4：stable 清单推送给 canary 安装，允许回正式。
        if (manifest.channel == UpdateChannel.Stable && installedChannel == UpdateChannel.Canary) {
            return ChannelDecision(
                offer = true,
                reason = "清单为 stable（${manifest.versionCode}），当前为 canary 通道，允许回到正式版",
            )
        }
        // 规则 5：通道一致且清单更新。
        return ChannelDecision(
            offer = true,
            reason = "同通道 ${manifest.channel.id} 且清单版本 ${manifest.versionCode} 高于当前 $currentVersionCode，可升级",
        )
    }
}
