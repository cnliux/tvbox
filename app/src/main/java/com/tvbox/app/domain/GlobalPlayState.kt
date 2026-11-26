package com.tvbox.app.domain

import android.os.Build
import java.util.Locale

/**
 * T3-22/T3-23/T3-24：应用级全局单例状态共享层。
 * 避免在各页面间传递引用，同时满足 JVM 单测环境（applicationContext 为 null）的兼容要求。
 */
object GlobalPlayState {

    /** 面包屑记录器：64 帧环形缓冲，跨播放页/设置页/崩溃捕获共享。 */
    val breadcrumb = RemoteBreadcrumb(capacity = 64)

    /** 菜单键连按计数器：用于触发健康面板。 */
    var menuPressCount: Int = 0
        set(value) { field = value.coerceAtLeast(0) }

    /** 最新一次失败码展示文本（含 E-xxx 前缀），供 OSD/日志共用，避免重复拼接。 */
    var lastFailureDisplayText: String? = null
}

/** 设备硬件标签，统一从 Build 获取，脱敏后作为报告后缀。 */
val deviceHardwareTag: String by lazy {
    val model = if (Build.VERSION.SDK_INT >= 21) {
        Build.MODEL.trim()
    } else {
        Build.HARDWARE.takeIf { it.isNotBlank() } ?: "Unknown"
    }
    val chip = runCatching { "${Build.CPU_ABI}/${Build.BRAND}" }.getOrDefault("")
    listOfNotNull(model, chip).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: "Unknown Device"
}

/** 当前 App 版本号，用于报告后缀展示。 */
val appVersionTag: String by lazy {
    try {
        val ctx = TvBoxApplicationRef.context
            ?: return@lazy "Unknown version"
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        buildString {
            append(info.versionName ?: "unknown")
            info.versionCode.let { if (it > 0) append("(v${info.versionCode})") }
        }
    } catch (_: Exception) { "version unknown" }
}

/** 模拟启动时间戳（毫秒 epoch）。首次访问后固定，保证同次运行一致。 */
internal var _bootMs: Long? = null
    private set
fun bootTimestampMs(): Long = _bootMs ?: System.currentTimeMillis().also { _bootMs = it }

/**
 * 应用上下文持有器：由 TvBoxApplication.onCreate 写入，供 JVM 单测返回 null 而不 crash。
 *
 * R1 内存安全：这里**只允许**写入 Application 上下文（生命周期 == 进程，不存在泄漏），
 * 绝不可塞 Activity/View 等短生命周期对象，否则就是典型 StaticFieldLeak。
 * 类型收窄为 Application 而非 Context，从编译期就挡住误传 Activity。
 */
object TvBoxApplicationRef {
    @Volatile
    var context: android.app.Application? = null
}

/**
 * T3-24：健康页快照数据。
 * 由 ChoreographerFrameTracker 和 MemoryGovernor 在运行时累积，按窗口汇出。
 */
data class HealthSnapshot(
    val ttfpP95Ms: Long = 0L,
    val longFrames: Int = 0,
    val throttled: Int = 0,
    val tier: MemoryGovernor.Tier = MemoryGovernor.Tier.RICH,
    val redactedExport: Boolean = true,
)
