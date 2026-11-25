package com.tvbox.app.domain

/**
 * UA 作用域解析与导出脱敏。
 *
 * 所有进入请求头的值一律经过 [UserAgentSetting.sanitize]（去首尾空白、剔除换行），
 * 因此输出要么是可直接放进 HTTP 头的字符串，要么是内置默认 UA，不可能是空串/带换行值。
 */
object UaScope {
    /** 支持的作用域；未知作用域按最小权限回退到内置默认 UA。 */
    val scopes: List<String> = listOf("api", "spider", "live", "epg", "playback", "webview")

    /** 脱敏时保留的尾部字符数。 */
    const val RedactTailChars: Int = 12

    /** 脱敏占位符：只保留尾部片段，前缀用省略号替代。 */
    private const val ELLIPSIS = "…"

    /**
     * 计算某个作用域的生效 UA。
     * - unified：统一用 [AppSettings.customUserAgent]，空则内置默认；
     * - scoped：用该作用域专属字段，空则回退全局自定义 UA，再空才回退内置默认；
     * - 未知 scope：一律内置默认，避免调用方拼错作用域时意外泄漏自定义 UA。
     */
    fun resolve(scope: String, settings: AppSettings): String {
        val normalized = scope.trim().lowercase()
        if (normalized !in scopes) return UserAgentSetting.Default
        if (settings.uaScopeMode.equals("unified", ignoreCase = true)) {
            return UserAgentSetting.resolve(settings.customUserAgent)
        }
        val scoped = when (normalized) {
            "api" -> settings.uaForApi
            "spider" -> settings.uaForSpider
            "live" -> settings.uaForLive
            "epg" -> settings.uaForEpg
            "playback" -> settings.uaForPlayback
            "webview" -> settings.uaForWebView
            else -> "" // 已在上面校验，保留分支使 when 穷尽
        }
        val sanitized = UserAgentSetting.sanitize(scoped)
        return if (sanitized.isNotBlank()) sanitized else UserAgentSetting.resolve(settings.customUserAgent)
    }

    /**
     * 脱敏展示：仅保留末尾 [RedactTailChars] 个字符，前面加省略号。
     * 短于阈值的 UA 本身不含可识别个人信息，原样返回（仍先清洗）。
     * 形如 `…Chrome/122 TVBox/1.0.36`（只展示尾部片段）。
     */
    fun redact(ua: String): String {
        val sanitized = UserAgentSetting.sanitize(ua)
        if (sanitized.length <= RedactTailChars) return sanitized
        return ELLIPSIS + sanitized.takeLast(RedactTailChars)
    }

    /**
     * 导出设置中的 UA 相关项。默认脱敏（[includeSensitive] = false），
     * 只有调用方显式要求时才写入完整值（用于本地备份/迁移）。
     */
    fun exportSensitive(settings: AppSettings, includeSensitive: Boolean = false): String {
        fun field(label: String, value: String) = if (includeSensitive) {
            "$label=${UserAgentSetting.sanitize(value)}"
        } else {
            "$label=${if (value.isBlank()) "<未设置>" else redact(value)}"
        }
        return buildList {
            add("[UA 设置导出]")
            add("uaScopeMode=${settings.uaScopeMode.trim().lowercase()}")
            add("uaExportRedacted=${settings.uaExportRedacted}")
            add(field("customUserAgent", settings.customUserAgent))
            add(field("uaForApi", settings.uaForApi))
            add(field("uaForSpider", settings.uaForSpider))
            add(field("uaForLive", settings.uaForLive))
            add(field("uaForEpg", settings.uaForEpg))
            add(field("uaForPlayback", settings.uaForPlayback))
            add(field("uaForWebView", settings.uaForWebView))
        }.joinToString("\n")
    }
}
