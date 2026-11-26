package com.tvbox.app.data

import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.Schemas
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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
 *
 * 解析约束：所有取字段都走 `as?` 安全转换， corrupt/异形 JSON 只返回固定文案的
 * [Result.error]，单个坏字段被跳过而不会抛 ClassCastException 中断整次恢复。
 *
 * #393 事务性 / #395 版本迁移：
 *  - 本对象只做「编解码」，不直接改设置。调用方（SourceBackupGateway → ViewModel）用 Mutex
 *    或等价的「导入中」串行标记保证同一时刻只有一次导入，并把结果作为整份 AppSettings
 *    一次性原子替换（单个 commit）：解析/校验失败时整份放弃，不会留下半套源配置。
 *  - 低版本备份（version &lt; VERSION）靠 `ignoreUnknownKeys` + `coerceInputValues` + 字段
 *    默认值解析：新增字段缺失时取 Kotlin 默认值，旧字段保留，见 [parse] 的 sites 兜底。
 */
object SourceConfigBackup {
    const val FORMAT = "tvbox-source-backup"
    const val VERSION = 1

    data class Payload(
        val lines: List<ApiLine>,
        val liveUrls: List<String>,
        val order: List<String>,
        val weights: Map<String, Int>,
        /**
         * S3-18：统一收藏段（[com.tvbox.app.domain.FavoriteRegistry.exportAll] 的原始 JSON 文本）。
         * 老备份没有该段时为空；导入时由调用方交给 FavoriteRegistry.importAll 落回各存储。
         */
        val favoritesJson: String? = null,
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

    /**
     * 兼容校验的「规范 body」：仅含 legacy 字段（format/version/sites/lives/order/weights），
     * checksum 只算这部分 —— 历史备份无 schema 字段时仍能通过，避免全量覆盖导致存量备份无法导入。
     */
    private fun legacyBodyJson(
        lines: List<ApiLine>,
        liveUrls: List<String>,
        order: List<String>,
        weights: Map<String, Int>,
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

    fun export(
        lines: List<ApiLine>,
        liveUrls: List<String>,
        order: List<String> = emptyList(),
        weights: Map<String, Int> = emptyMap(),
        /**
         * S3-18：统一收藏段（FavoriteRegistry.exportAll 的 JSON 文本）。null/空白/非法 JSON 时
         * 不写 favorites 段；该段放在 legacy body 之外，checksum 仍只覆盖 legacy 字段，
         * 老客户端 ignoreUnknownKeys 自动忽略，存量备份不受影响。
         */
        favorites: String? = null,
    ): String {
        // #395/T7-44：新备份带 schema 信封，旧客户端 ignoreUnknownKeys=true 自动忽略。
        // checksum 只覆盖 legacy body（与历史版本格式一致），保证存量备份仍可校验。
        val legacyBody = legacyBodyJson(lines, liveUrls, order, weights)
        val checksum = sha256(legacyBody)
        return buildJsonObject {
            put("schema", Schemas.SOURCE_CONFIG)
            put("kind", Schemas.Kind.SourceConfig.id)
            put("minReader", Schemas.SOURCE_CONFIG)
            put("createdBy", "${Schemas.current(Schemas.Kind.SourceConfig)}/${VERSION}")
            put("checksum", checksum)
            // 展开 legacy body 字段（顺序与历史输出保持一致）
            // R3：legacy body 来自历史导入数据，可能不是 JSON 对象（数组/字符串/null）。
            // 用 as? 安全转换，非对象时跳过展开而不是抛 ClassCastException 中断整个导出。
            (Json.parseToJsonElement(legacyBody) as? JsonObject)
                ?.entries?.forEach { (key, value) -> put(key, value) }
            // S3-18：favorites 段（kind + id 列表）；解析失败静默跳过，不让收藏问题阻断源备份。
            favorites
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { Json.parseToJsonElement(raw) }.getOrNull() }
                ?.let { put("favorites", it) }
        }.toString()
    }

    /** 计算 UTF-8 文本的 SHA-256 十六进制摘要（小写）。 */
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun parse(raw: String): Result {
        val root = try {
            json.parseToJsonElement(raw.trim()) as? JsonObject
                ?: return Result(null, "备份根节点不是对象")
        } catch (_: Exception) {
            // 不回传异常原文：里面可能带文件内容片段。
            return Result(null, "备份 JSON 解析失败")
        }
        val format = (root["format"] as? JsonPrimitive)?.content
        if (format != FORMAT) {
            return Result(null, "不是本应用的源配置备份文件")
        }
        val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Result(null, "备份缺少有效的版本号")
        if (version > VERSION) {
            return Result(null, "备份版本 $version 高于当前支持的 $VERSION，请先升级应用")
        }
        // #395：version < VERSION 的低版本备份照样放行——后续字段全部走「缺失即默认值」的兼容解析。
        val isLegacyBackup = version < VERSION

        // #188/#394/T7-44：带 checksum 的备份先校验完整性。
        // checksum 只对 legacy body（不含 schema/kind/minReader/createdBy/checksum）计算，
        // 保证历史备份（无信封字段）与新版备份仍能用同一把钥匙校验。
        val expectedChecksum = (root["checksum"] as? JsonPrimitive)?.content?.trim()
        if (!expectedChecksum.isNullOrBlank()) {
            val legacyKeys = setOf("format", "version", "sites", "lives", "order", "weights")
            val body = buildJsonObject {
                root.entries.forEach { (key, value) ->
                    if (key != "checksum" && key in legacyKeys) put(key, value)
                }
            }
            if (!sha256(body.toString()).equals(expectedChecksum, ignoreCase = true)) {
                return Result(null, "备份校验失败：文件内容已损坏或被修改，请勿导入")
            }
        }

        // #395：低版本备份若没有 sites 字段，按空列表兼容（后续 lives/order/weights 本来就用了
        // orEmpty() 兜底）；当前版本仍要求 sites 存在，避免把损坏的新版备份静默当成空配置。
        val sites = (root["sites"] as? JsonArray)
            ?: if (isLegacyBackup) JsonArray(emptyList()) else return Result(null, "备份缺少 sites 列表")
        val lines = sites.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val api = (item["api"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (api.isBlank()) return@mapNotNull null
            val id = (item["id"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { api }
            val name = (item["name"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { id }
            ApiLine(id = id, name = name, baseUrls = listOf(api))
        }.distinctBy { it.baseUrls.firstOrNull().orEmpty() }

        val liveUrls = (root["lives"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
        val order = (root["order"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }
        val weights = (root["weights"] as? JsonObject).orEmpty()
            .mapNotNull { (id, element) ->
                val value = (element as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: return@mapNotNull null
                id to value.coerceAtLeast(0)
            }
            .toMap()

        val favoritesJson = root["favorites"]?.takeIf { it != JsonNull }?.toString()
        return Result(Payload(lines, liveUrls, order, weights, favoritesJson), null)
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
