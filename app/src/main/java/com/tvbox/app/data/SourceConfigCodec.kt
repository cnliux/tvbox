package com.tvbox.app.data

import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.CustomVideoApiLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses and produces video source configuration compatible with TVBox and sources.json formats.
 *
 * Accepted import payloads:
 * - TVBox config: `{"sites": [{"key": "...", "name": "...", "type": 1, "api": "..."}], "lives": [{"name":"...","type":"txt","url":"..."}]}`
 * - StoreHouse style: `{"storeHouse": [{"sourceName": "...", "sourceUrl": "..."}]}`
 * - sources.json: `[{"id": 1, "name": "...", "api_url": "...", "status": 1, "priority": 1}]` or `{"data": [...]}`
 *
 * Export produces the same TVBox config (sites + lives) and the sources.json array.
 */
object SourceConfigCodec {

    data class ImportedSources(
        val lines: List<CustomVideoApiLine>,
        val liveUrls: List<String>,
        val skipped: Int,
    )

    data class Result(
        val imported: ImportedSources?,
        val error: String?,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseImport(raw: String): Result {
        val text = raw.trim()
        if (text.isBlank()) return Result(imported = null, error = "配置内容为空，请粘贴或输入 URL 后再导入")
        val root = try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            return Result(imported = null, error = "JSON 解析失败：${error.message ?: "格式不合法"}")
        }

        return try {
            when (root) {
                is JsonArray -> parseSourcesArray(root)
                is JsonObject -> parseObject(root)
                else -> Result(imported = null, error = "配置格式不合法，请提供 TVBox JSON 或 sources.json")
            }
        } catch (error: Exception) {
            Result(imported = null, error = "配置解析失败：${error.message ?: "格式不合法"}")
        }
    }

    fun exportTvbox(lines: List<ApiLine>, liveUrls: List<String>): String {
        val payload = buildString {
            append("{\n")
            append("  \"sites\": [\n")
            append(
                lines.joinToString(",\n") { line ->
                    val safeName = line.name.replace("\\", "\\\\").replace("\"", "\\\"")
                    val safeUrl = (line.baseUrls.firstOrNull() ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
                    "    {\"key\": \"${line.id.replace('"', '_')}\", \"name\": \"$safeName\", \"type\": 1, \"api\": \"$safeUrl\", \"searchable\": 1, \"quickSearch\": 1, \"filterable\": 0}"
                },
            )
            append("\n  ]")
            if (liveUrls.isNotEmpty()) {
                append(",\n  \"lives\": [\n")
                append(
                    liveUrls.joinToString(",\n") { url ->
                        val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
                        "    {\"name\": \"直播\", \"type\": \"txt\", \"url\": \"$safeUrl\"}"
                    },
                )
                append("\n  ]")
            }
            append("\n}\n")
        }
        return payload
    }

    fun exportSourcesJson(lines: List<ApiLine>): String {
        val payload = buildString {
            append("[\n")
            append(
                lines.mapIndexed { index, line ->
                    val safeName = json.encodeToString(line.name)
                    val safeUrl = json.encodeToString(line.baseUrls.firstOrNull() ?: "")
                    "  {\"id\": ${index + 1}, \"name\": $safeName, \"api_url\": $safeUrl, \"status\": 1, \"priority\": ${index + 1}}"
                }.joinToString(",\n"),
            )
            append("\n]\n")
        }
        return payload
    }

    private fun parseObject(root: JsonObject): Result {
        val tvboxSites = root["sites"] as? JsonArray
        if (tvboxSites != null) {
            val lines = mutableListOf<CustomVideoApiLine>()
            val liveUrls = mutableListOf<String>()
            var skipped = 0
            tvboxSites.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val type = item.intOrNull("type")
                // TVBox type 0/other are crawler or shell sources which this app cannot fetch.
                val target = item.stringOrNull("api") ?: item.stringOrNull("url")
                if (type != null && type != 1L) {
                    skipped++
                    return@forEach
                }
                if (target.isNullOrBlank()) return@forEach
                val normalized = normalize(target) ?: run { skipped++; return@forEach }
                val name = (item.stringOrNull("name") ?: item.stringOrNull("key") ?: "").trim()
                    .ifBlank { normalized.substringAfter("//").substringBefore('/') }
                lines += CustomVideoApiLine(
                    id = "custom_${stableId(normalized)}",
                    name = name,
                    baseUrl = normalized,
                )
            }
            // 排除 URL 重复的源（与 TVBox/网页版导入一致，带 query 的地址按去掉 query 后比对）。
            val distinctLines = normalizeLines(lines)
            root["lives"]?.let { livesElement ->
                if (livesElement is JsonArray) {
                    livesElement.forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        val url = item.stringOrNull("url") ?: return@forEach
                        url.split('#')
                            .mapNotNull { fragment -> normalize(fragment) }
                            .forEach { normalized -> if (normalized !in liveUrls) liveUrls += normalized }
                    }
                }
            }
            return Result(
                imported = ImportedSources(lines = distinctLines, liveUrls = liveUrls.distinct(), skipped = skipped),
                error = null,
            )
        }

        val storeHouse = root["storeHouse"] as? JsonArray
        if (storeHouse != null) {
            val lines = mutableListOf<CustomVideoApiLine>()
            var skipped = 0
            storeHouse.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val sourceUrl = item.stringOrNull("sourceUrl") ?: return@forEach
                val normalized = normalize(sourceUrl) ?: run { skipped++; return@forEach }
                lines += CustomVideoApiLine(
                    id = "custom_${stableId(normalized)}",
                    name = item.stringOrNull("sourceName")?.trim()?.ifBlank { null }
                        ?: normalized.substringAfter("//").substringBefore('/'),
                    baseUrl = normalized,
                )
            }
            return Result(
                imported = ImportedSources(lines = normalizeLines(lines), liveUrls = emptyList(), skipped = skipped),
                error = null,
            )
        }

        val data = root["data"] as? JsonArray
        if (data != null) {
            return parseSourcesArray(data)
        }

        return Result(imported = null, error = "配置缺少 sites / storeHouse / data 字段，请检查是否为 TVBox 或 sources.json")
    }

    private fun parseSourcesArray(array: JsonArray): Result {
        val lines = mutableListOf<CustomVideoApiLine>()
        var skipped = 0
        array.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val rawUrl = item.stringOrNull("api_url") ?: item.stringOrNull("api") ?: item.stringOrNull("url")
                ?: item.stringOrNull("sourceUrl")
            if (rawUrl.isNullOrBlank()) return@forEach
            val normalized = normalize(rawUrl) ?: run { skipped++; return@forEach }
            lines += CustomVideoApiLine(
                id = "custom_${stableId(normalized)}",
                name = (item.stringOrNull("name") ?: "").trim()
                    .ifBlank { normalized.substringAfter("//").substringBefore('/') },
                baseUrl = normalized,
            )
        }
        return Result(
            imported = ImportedSources(lines = normalizeLines(lines), liveUrls = emptyList(), skipped = skipped),
            error = null,
        )
    }


    /** Strips query/fragment, enforces trailing slash like every other MacCms source URL inside the app. */
    private fun normalize(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        val http = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        if (!http) return null
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
        if (withoutQuery.length <= "http://".length) return null
        return "$withoutQuery/"
    }

    private fun normalizeLines(lines: List<CustomVideoApiLine>): List<CustomVideoApiLine> {
        val seen = mutableSetOf<String>()
        return lines.filter { line -> seen.add(line.baseUrl) }
    }

    private fun stableId(baseUrl: String): String {
        return Integer.toUnsignedString(baseUrl.lowercase().hashCode(), 36)
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.intOrNull(key: String): Long? {
        val primitive = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return primitive.content.toLongOrNull()
    }
}
