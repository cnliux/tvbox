package com.tvbox.app.util

/**
 * 结构化脱敏包装类型（对应《聚合视频TVBox全项目优化方案》P46）。
 *
 * 设计意图：把「敏感字符串」在类型系统里显式标注出来，防止新代码随手把它拼进日志或
 * `toString()`。包装类型的 `toString()` 只返回脱敏摘要，即使被意外插入字符串模板，
 * 也不会泄漏完整地址、token、Cookie 等内容。
 *
 * 使用约定：
 * - 新代码（网络、历史、OTA、Go 边界）优先把敏感值包成 [SensitiveString]，日志输出统一走
 *   [StructuredLog.event]。
 * - 老代码继续用 [logSafeUrl] / [logSafeText] 仍然合法，本次不做批量替换，避免大范围改动。
 * - 实际网络请求需要原始值时，必须显式调用 [reveal]，并在调用点注释说明用途。
 */
@JvmInline
value class SensitiveString constructor(private val value: String) {

    /**
     * 只返回脱敏摘要：形如 `片名***[len=12]`（首 4 字符 + `***` + 原始长度）。
     *
     * 注意：这里刻意覆盖 `Any.toString`，防止 `"url=$sensitive"` 这类模板拼接泄漏原文。
     * 永远不会返回 [value] 的完整内容。
     */
    override fun toString(): String = maskedSummary()

    /** 脱敏摘要：首 4 字符 + `***` + 长度标记，用于人读日志。 */
    fun maskedSummary(): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "<空>"
        val head = trimmed.take(4)
        return "$head***[len=${trimmed.length}]"
    }

    /** URL 日志视图：委托给现有 [logSafeUrl]，保留协议 + 主机（必要时含端口）。 */
    fun logSafe(): String = logSafeUrl(value)

    /** 整段文本日志视图：委托给现有 [logSafeText]，用于异常 message 等夹杂地址的长文本。 */
    fun logSafeText(): String = logSafeText(value)

    /** 日志占位：空值时返回固定占位符，便于对齐列宽并一眼识别缺失。 */
    fun orEmptyForLog(): String = if (value.isBlank()) "<空>" else logSafe()

    /**
     * 显式取出原始值：仅用于实际网络请求 / 数据写入，禁止用于日志输出。
     *
     * 调用点必须能回答「这里为什么需要原文」，review 时按此检查。
     */
    fun reveal(): String = value
}

/** 工厂：null 或空白入参统一返回 null，避免调用方各自判空。 */
fun sensitive(raw: String?): SensitiveString? {
    if (raw.isNullOrBlank()) return null
    return SensitiveString(raw)
}

/** 可空包装的日志安全视图：null 直接给固定占位符，避免调用方再做判空。 */
fun SensitiveString?.logSafe(): String = this?.logSafe() ?: "<空>"

/** 可空包装的整段文本日志视图。 */
fun SensitiveString?.logSafeText(): String = this?.logSafeText() ?: "<空>"

/** 可空包装的日志占位视图：null / 空白返回 "<空>"。 */
fun SensitiveString?.orEmptyForLog(): String = this?.orEmptyForLog() ?: "<空>"

/**
 * 结构化日志字段枚举：日志 facade 只接受这里的字段名，避免自由文本字段绕过脱敏规则。
 *
 * 覆盖方案点名的字段：MediaName / MediaUrl / UserAgent / AccessToken / ConfigUrl。
 */
enum class LogField {
    MediaName,
    MediaUrl,
    UserAgent,
    AccessToken,
    ConfigUrl,
    Other
}

/**
 * 需要完全遮蔽（不显示前 4 字符）的字段：凭证类与可定位用户的配置类地址。
 *
 * 片名 / 普通播放地址保留首 4 字符便于人工排查；下面这些字段一律 `***[len=N]`。
 */
private val FULLY_MASKED_FIELDS: Set<LogField> = setOf(LogField.AccessToken, LogField.ConfigUrl)

/**
 * 统一结构化日志出口：所有敏感事件的日志都必须经过这里。
 *
 * 输出形如 `field=MediaName value=片名***[len=12] message=...`，内部再走一次
 * [logSafeText] 做二次脱敏，防止 message 里夹杂的地址漏出。
 *
 * 返回渲染后的整行，便于单元测试直接断言（不依赖 stdout / Log 捕获）；
 * 真实项目接入 Timber 时，返回值仍可用于测试而输出侧替换实现。
 */
object StructuredLog {
    fun event(field: LogField, value: SensitiveString?, message: String): String {
        val rendered = when {
            value == null -> "<空>"
            field in FULLY_MASKED_FIELDS -> "***[len=${value.reveal().trim().length}]"
            else -> value.maskedSummary()
        }
        val safeMessage = logSafeText(message)
        val line = "field=${field.name} value=$rendered message=$safeMessage"
        // 真实项目里接入 Timber / 统一 logger 时替换此行；当前用 android.util.Log，
        // 单元测试环境下退化为 println，保证 smoke test 可观测。
        runCatching { android.util.Log.d("StructuredLog", line) }
            .onFailure { println(line) }
        return line
    }
}

/**
 * 旧日志 facade（保留向后兼容）：等价于 [StructuredLog.event] 去掉 message。
 * 新代码请优先使用 [StructuredLog.event]。
 */
object SensitiveLog {
    fun event(field: SensitiveField, value: SensitiveString?) {
        val line = "sensitive=${field.name} value=${value.logSafe()}"
        runCatching { android.util.Log.d("SensitiveLog", line) }
            .onFailure { println(line) }
    }
}

/**
 * 旧字段枚举（保留向后兼容）：历史调用点仍在使用。
 * 新代码请改用 [LogField]，它覆盖方案点名的 MediaName / MediaUrl / UserAgent 等。
 */
enum class SensitiveField {
    Authorization,
    Cookie,
    Token,
    ApiKey,
    SourceUrl,
    Ua,
    Phone,
    RoomId,
    Custom
}