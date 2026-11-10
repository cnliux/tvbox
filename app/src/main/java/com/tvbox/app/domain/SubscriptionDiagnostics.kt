package com.tvbox.app.domain

/**
 * 源导入的分类诊断：拉取失败时给出可操作提示并保留上次成功配置，
 * JSON 导入前做 schema 校验并列出被跳过条目及原因。
 */

enum class SubscriptionErrorKind { Dns, Timeout, Connection, Http, NotJson, InvalidSchema, Unknown }

data class SubscriptionError(
    val kind: SubscriptionErrorKind,
    val userMessage: String,
    /** 失败时不清空已生效的配置。 */
    val keepPreviousConfig: Boolean = true,
)

fun classifySubscriptionError(
    message: String?,
    httpStatus: Int? = null,
    payload: String? = null,
): SubscriptionError {
    val text = message.orEmpty().lowercase()
    val body = payload?.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    // "**" 是订阅 Base64 加密壳前缀，不应被误判为「非 JSON」。
    val notJson = body != null && body.isNotEmpty() &&
        !body.startsWith("{") && !body.startsWith("[") && !body.contains("**")
    val invalidSchema = body != null && body.startsWith("{") &&
        !body.contains("\"sites\"") && !body.contains("\"api\"") && !body.contains("\"url\"")
    return when {
        text.contains("unable to resolve host") || text.contains("dns") || text.contains("unknownhost") ->
            SubscriptionError(SubscriptionErrorKind.Dns, "域名解析失败，请检查订阅地址或网络")
        text.contains("timeout") || text.contains("timed out") || text.contains("超时") ->
            SubscriptionError(SubscriptionErrorKind.Timeout, "拉取超时，请稍后重试或更换网络")
        httpStatus != null && httpStatus >= 400 ->
            SubscriptionError(SubscriptionErrorKind.Http, "订阅地址返回 HTTP $httpStatus")
        notJson ->
            SubscriptionError(SubscriptionErrorKind.NotJson, "返回内容不是 JSON，可能是网页或跳转页")
        invalidSchema ->
            SubscriptionError(SubscriptionErrorKind.InvalidSchema, "JSON 缺少可识别的站点或接口字段")
        text.contains("connection") || text.contains("failed to connect") || text.contains("ioexception") ->
            SubscriptionError(SubscriptionErrorKind.Connection, "连接失败，请检查网络")
        else ->
            SubscriptionError(SubscriptionErrorKind.Unknown, "订阅拉取失败，已保留上次成功配置")
    }
}

data class SourceEntryDraft(val name: String, val type: String, val url: String)

data class SkippedSourceEntry(val index: Int, val reason: String)

data class SourceImportPreview(
    val accepted: List<SourceEntryDraft>,
    val skipped: List<SkippedSourceEntry>,
) {
    val acceptedCount: Int get() = accepted.size
}

private val allowedSourceTypes = setOf("maccms", "json", "txt", "m3u", "xml", "custom")

/** 导入前预览：逐条校验，列出跳过原因，合法条目原样带入预览。 */
fun validateSourceEntries(raw: List<Map<String, String?>>): SourceImportPreview {
    val accepted = mutableListOf<SourceEntryDraft>()
    val skipped = mutableListOf<SkippedSourceEntry>()
    raw.forEachIndexed { index, entry ->
        val name = entry["name"]?.trim().takeUnless { it.isNullOrEmpty() }
        val type = entry["type"]?.trim()?.lowercase()
        val url = entry["url"]?.trim().takeUnless { it.isNullOrEmpty() }
        val reason = when {
            name == null -> "缺少名称"
            url == null -> "缺少接口地址"
            !(url.startsWith("http://") || url.startsWith("https://")) -> "接口地址需为 http/https"
            type != null && type !in allowedSourceTypes -> "不支持的类型：$type"
            else -> null
        }
        if (reason == null) {
            accepted += SourceEntryDraft(name.orEmpty(), type ?: "custom", url.orEmpty())
        } else {
            skipped += SkippedSourceEntry(index, reason)
        }
    }
    return SourceImportPreview(accepted, skipped)
}
