package com.tvbox.app.domain

/**
 * 局域网解析服务启用守卫（纯逻辑）。
 *
 * 该服务默认关闭；首次开启前必须确认安全提示，未确认时任何"启用入口"都应返回
 * [NEEDS_CONFIRMATION] 状态并拒绝启动，而不是静默放行。设置页只负责写入偏好，
 * 真正的启用判断集中在这里，避免各调用点各自解释两个布尔字段。
 */
object LanResolverGuard {
    /** 未确认安全提示时的明确状态文案（调用方直接展示/记录）。 */
    const val NEEDS_CONFIRMATION: String = "需先确认安全提示"

    private const val DISABLED = "局域网解析服务未启用"
    private const val ALLOWED = "局域网解析服务已启用"

    /** 计算启用状态：未启用 < 未确认 < 已启用。 */
    fun enableStatus(settings: AppSettings): String = when {
        !settings.lanResolverEnabled -> DISABLED
        !settings.lanResolverConfirmed -> NEEDS_CONFIRMATION
        else -> ALLOWED
    }

    /** 是否允许真正启动局域网解析服务。 */
    fun isAllowed(settings: AppSettings): Boolean = enableStatus(settings) == ALLOWED

    /**
     * 启用入口统一调用：允许时返回 null，否则返回应展示的阻断原因。
     * 这样调用点不需要自己拼"未确认"文案，也不会遗漏确认判断。
     */
    fun blockReason(settings: AppSettings): String? =
        enableStatus(settings).takeIf { it == NEEDS_CONFIRMATION }
}
