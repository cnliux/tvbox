package com.tvbox.app.data

import com.tvbox.app.domain.ApiLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 源配置的备份 / 恢复（第 90 轮）。
 *
 * 与 [SourceConfigCodec] 的区别：后者导出的是给 TVBox / sources.json 生态用的**接口配置**，
 * 这里导出的是本应用自己的**完整源状态**（含排序、权重与直播订阅），用于换机恢复。
 *
 * 隐私约束：备份只包含源标识、名称与订阅地址，不写任何 API Key / token / 口令；
 * [hasSensitiveKeys] 用于导出前自检，导入时 `ignoreUnknownKeys` 会丢弃额外字段，
 * 不会把密钥带回本机设置。
 */
object SourceConfigBackup {
    const val FORMAT = "tvbox-source-backup"
    const val VERSION = 1

    data class Payload(
        val lines: List<ApiLine>,
        val liveUrls: List<String>,
        val order: List<String>,
        val weights: Map<String, Int>,
    )

    data class Result(
        val payload: Payload?,
        val error: String?,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val sensitiveKeyPattern = Regex(
        "(?i)(api[_-]?key|token|secret|password|passwd|authorization|cookie|signature|access[_-]?key)",
    )

    fun export(
        lines: List<ApiLine>,
        liveUrls: List<String>,
        order: List<String> = emptyList(),
        weights: Map<String, Int> = emptyMap(),
    ): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        putJsonArray("sites") {
            lines.forEach { line ->
                add(
                    buildJsonObject {
                        put("id", line.id)
                        put("name", line.name)
                        put("api", line.baseUrls.firstOrNull().orEmpty())
                    },
                )
            }
        }
        putJsonArray("lives") { liveUrls.forEach { add(it) } }
        putJsonArray("order") { order.forEach { add(it) } }
        put(
            "weights",
            buildJsonObject {
                weights.forEach { (id, weight) -> put(id, weight) }
            },
        )
    }.toString()

    fun parse(raw: String): Result {
        val root = try {
            json.parseToJsonElement(raw.trim()).jsonObject
        } catch (error: Exception) {
            return Result(null, "备份 JSON 解析失败：${error.message ?: "格式不合法"}")
        }
        if (root["format"]?.jsonPrimitive?.content != FORMAT) {
            return Result(null, "不是本应用的源配置备份文件")
        }
        val version = root["version"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return Result(null, "备份缺少有效的版本号")
        if (version > VERSION) {
            return Result(null, "备份版本 $version 高于当前支持的 $VERSION，请先升级应用")
        }

        val sites = root["sites"] as? JsonArray
            ?: return Result(null, "备份缺少 sites 列表")
        val lines = sites.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val api = item["api"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (api.isBlank()) return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { api }
            val name = item["name"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { id }
            ApiLine(id = id, name = name, baseUrls = listOf(api))
        }.distinctBy { it.baseUrls.firstOrNull().orEmpty() }

        val liveUrls = (root["lives"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.content.trim().takeIf(String::isNotBlank) }
            .distinct()
        val order = (root["order"] as? JsonArray).orEmpty()
            .map { it.jsonPrimitive.content }
        val weights = (root["weights"] as? JsonObject).orEmpty()
            .mapNotNull { (id, element) ->
                val value = runCatching { element.jsonPrimitive.content.toInt() }.getOrNull()
                    ?: return@mapNotNull null
                id to value.coerceAtLeast(0)
            }
            .toMap()

        return Result(Payload(lines, liveUrls, order, weights), null)
    }

    /** 导出前自检：JSON 任意层级的键名命中敏感词就应拒绝导出。 */
    fun hasSensitiveKeys(raw: String): Boolean {
        val element = try {
            json.parseToJsonElement(raw.trim())
        } catch (_: Exception) {
            return false
        }
        return containsSensitiveKey(element)
    }

    private fun containsSensitiveKey(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.entries.any { (key, value) ->
            sensitiveKeyPattern.containsMatchIn(key) || containsSensitiveKey(value)
        }
        is JsonArray -> element.any(::containsSensitiveKey)
        else -> false
    }
}
