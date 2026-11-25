package com.tvbox.app.domain

/**
 * 恢复出厂三档（纯逻辑骨架）。
 *
 * 这里只负责「哪一档执行哪些步骤、需要什么确认、明确保留什么」，
 * 真正的删除动作由调用方通过 [FactoryResetDeps] 注入；默认依赖为空实现，
 * 因此本对象本身**不会**删除任何 APK / AAR / 崩溃日志。
 */

enum class ResetTier(
    val displayName: String,
    val description: String,
) {
    /** 仅清偏好设置：账号无关的 UI/播放偏好回到默认值。 */
    PreferencesOnly("仅偏好设置", "清除播放/界面等偏好设置，视频源、网络缓存与历史记录保留。"),

    /** 偏好 + 视频源 + 网络缓存：清空内容接入配置，但不碰本地媒体与历史。 */
    PreferencesSourcesNetwork("偏好与源", "在「仅偏好设置」基础上清除视频源配置与网络缓存。"),

    /** 全部：上面所有项 + 观看历史；APK、AAR 与崩溃日志始终保留。 */
    All("全部", "清除全部可重置数据；安装包、依赖包与崩溃日志保留，需额外输入 DELETE 确认。"),
    ;
}

/** 重置动作的依赖注入点；默认实现全部为空操作，骨架本身不执行真实删除。 */
interface FactoryResetDeps {
    suspend fun clearPreferences() {}
    suspend fun clearSources() {}
    suspend fun clearNetworkCaches() {}
    suspend fun clearPlaybackHistory() {}

    companion object {
        /** 安全默认：不执行任何真实删除，便于 UI 预览/单测。 */
        val Noop: FactoryResetDeps = object : FactoryResetDeps {}
    }
}

data class FactoryResetResult(
    val tier: ResetTier,
    /** 已执行的步骤文案（按档位顺序）。 */
    val steps: List<String>,
    /** 明确保留、不会被本流程删除的内容。 */
    val retained: List<String>,
    /** 供 UI 二次确认时展示的文案。 */
    val confirmationText: String,
    val message: String,
)

object FactoryReset {
    /** All 档必须显式传入的确认令牌。 */
    const val DELETE_TOKEN: String = "DELETE"

    /** 二次确认文案，UI 在弹窗里原样展示。 */
    fun confirmationText(tier: ResetTier): String = when (tier) {
        ResetTier.PreferencesOnly ->
            "将清除偏好设置并恢复默认值；视频源、网络缓存与观看历史保留。是否继续？"
        ResetTier.PreferencesSourcesNetwork ->
            "将清除偏好设置、视频源配置与网络缓存；观看历史保留。是否继续？"
        ResetTier.All ->
            "将清除偏好设置、视频源配置、网络缓存与观看历史。安装包（APK）、依赖包（AAR）与崩溃日志不会被删除；请输入 DELETE 确认。"
    }

    /**
     * 执行重置骨架。
     *
     * All 档要求 [confirmationToken] 严格等于 [DELETE_TOKEN]，否则在任何依赖被调用前抛错，
     * 避免误触导致不可逆操作。其余档位不需要令牌。
     */
    suspend fun reset(
        tier: ResetTier,
        deps: FactoryResetDeps = FactoryResetDeps.Noop,
        confirmationToken: String? = null,
    ): FactoryResetResult {
        if (tier == ResetTier.All && confirmationToken != DELETE_TOKEN) {
            throw IllegalArgumentException("「全部」档需传入确认令牌 \"$DELETE_TOKEN\"；未执行任何操作")
        }
        val steps = mutableListOf<String>()
        when (tier) {
            ResetTier.PreferencesOnly -> {
                deps.clearPreferences()
                steps += "已清除偏好设置"
            }
            ResetTier.PreferencesSourcesNetwork -> {
                deps.clearPreferences()
                steps += "已清除偏好设置"
                deps.clearSources()
                steps += "已清除视频源配置"
                deps.clearNetworkCaches()
                steps += "已清除网络缓存"
            }
            ResetTier.All -> {
                deps.clearPreferences()
                steps += "已清除偏好设置"
                deps.clearSources()
                steps += "已清除视频源配置"
                deps.clearNetworkCaches()
                steps += "已清除网络缓存"
                deps.clearPlaybackHistory()
                steps += "已清除观看历史"
            }
        }
        val retained = if (tier == ResetTier.All) {
            listOf("APK 安装包", "AAR 依赖包", "崩溃日志")
        } else {
            emptyList()
        }
        val retainSuffix = if (retained.isEmpty()) "" else "；保留：${retained.joinToString("、")}"
        return FactoryResetResult(
            tier = tier,
            steps = steps,
            retained = retained,
            confirmationText = confirmationText(tier),
            message = "${tier.displayName}完成：${steps.joinToString("、")}$retainSuffix",
        )
    }
}
