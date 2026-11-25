package com.tvbox.app.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * T5-34：不可信 JSON 边界的唯一解码出口。
 *
 * 源站返回 100% 不可信：`!!` / `as` 会把「少一层嵌套」变成白屏。
 * 这里统一 `Result<T>` + 默认值 + ignoreUnknownKeys，失败方自行带 E-1xx 码
 * 与遥测标签；调用方不再需要解引用断言。
 */
object UntrustedJson {

    /** 单一真源：未知字段一律忽略（也是 OTA 的生命线），null 落到默认值。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun <T> decode(raw: String, serializer: KSerializer<T>): Result<T> =
        runCatching { json.decodeFromString(serializer, raw.trim().removePrefix("\uFEFF")) }

    /** 解不出时回落默认值，绝不抛；用于「详情页至少显示原始名 + ⚠」的兜底。 */
    fun <T> decodeOrDefault(raw: String, serializer: KSerializer<T>, fallback: T): T =
        decode(raw, serializer).getOrDefault(fallback)

    /** HTML 错误页/空响应不是 JSON，提前拦下便于打 E-101 而不是 NPE。 */
    fun looksLikeJson(raw: String): Boolean {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }
}
