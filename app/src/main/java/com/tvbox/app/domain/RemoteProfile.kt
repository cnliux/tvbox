package com.tvbox.app.domain

import android.view.KeyEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * P1-09：`remote_profile.json` 的纯领域模型 + 编解码（schemaVersion 2）。
 *
 * 设计口径与 `SourceConfigBackup` 一致：先构造**不含 checksum 的规范 body**，
 * 对 `body.toString()` 取 SHA-256；导入时剔除 checksum 字段后同法重算比对，
 * 校验失败整份拒绝，绝不把半套配置写进设置。旧格式（无 checksum）放行以兼容手工编辑。
 *
 * 本文件只做数据，不碰 Activity / DataStore；设置页通过 [RemoteProfileDialog] 消费，
 * 真正落盘仍走既有的 `AppSettings.remoteKeyBindings` → `SettingsPreferencesCodec` 通道。
 */

/** 手感参数：预设之间的可见差异，随 profile 一起导出（具体消费方逐步接入）。 */
data class RemoteFeel(
    val seekStepMs: Long = 10_000L,
    val seekCurve: String = "standard",
    val longPressMs: Int = 400,
    val debounceCrossKeyMs: Int = 300,
    val autoRepeatCap: Int = 30,
    val directTuneTimeoutMs: Int = 2000,
    val focusRingDp: Int = 3,
    val listScroll: String = "smooth",
    val announceLevel: String = "standard",
) {
    fun summary(): String = buildList {
        add("单击 ${seekStepMs / 1000}s")
        add("防抖 ${debounceCrossKeyMs}ms")
        add("长按 ${longPressMs}ms")
        add("选台 ${if (directTuneTimeoutMs == 0) "手动" else "${directTuneTimeoutMs}ms"}")
        add("焦点框 ${focusRingDp}dp")
        add("播报 $announceLevel")
    }.joinToString(" · ")
}

/** 三套出厂预设；当前预设只影响绑定表与手感参数，已学习的硬件映射会原样保留。 */
enum class RemotePreset(
    val id: String,
    val displayName: String,
    val description: String,
    val bindings: RemoteKeyBindings,
    val feel: RemoteFeel,
) {
    Standard(
        "standard", "标准", "全键遥控器（默认）：MEDIA_* 原生键 + DPAD 双绑",
        RemoteKeyBindings.Default.copy(preset = "standard"),
        RemoteFeel(),
    ),
    Legacy6(
        "legacy6", "老盒子兼容", "6 键无媒体键：媒体动作压到 DPAD + OK（长按裁决）",
        RemoteKeyBindings(
            preset = "legacy6",
            custom = mapOf(
                RemoteKeyAction.PlayPause.id to setOf(KeyEvent.KEYCODE_DPAD_CENTER),
                RemoteKeyAction.SeekBack.id to setOf(KeyEvent.KEYCODE_DPAD_LEFT),
                RemoteKeyAction.SeekForward.id to setOf(KeyEvent.KEYCODE_DPAD_RIGHT),
                RemoteKeyAction.Previous.id to setOf(KeyEvent.KEYCODE_DPAD_UP),
                RemoteKeyAction.Next.id to setOf(KeyEvent.KEYCODE_DPAD_DOWN),
            ),
        ),
        RemoteFeel(
            seekStepMs = 15_000L, seekCurve = "gentle", debounceCrossKeyMs = 450,
            directTuneTimeoutMs = 3000, autoRepeatCap = 0,
        ),
    ),
    Accessibility(
        "a11y", "无障碍大焦点", "步长 5s / 防抖 450ms / 焦点框 4dp / 选台手动确认 / 播报全",
        RemoteKeyBindings.Default.copy(preset = "a11y"),
        RemoteFeel(
            seekStepMs = 5_000L, seekCurve = "gentle", debounceCrossKeyMs = 450,
            directTuneTimeoutMs = 0, focusRingDp = 4, announceLevel = "full",
        ),
    ),
    ;

    companion object {
        fun fromId(id: String?): RemotePreset? = entries.firstOrNull { it.id == id }
    }
}

/** 一个物理键被多个动作同时认领（冲突可视化的数据源）。 */
data class RemoteBindingConflict(
    val keyCode: Int,
    val actions: List<RemoteKeyAction>,
) {
    val summary: String
        get() = "0x%03X".format(keyCode) + " 同时绑定：" + actions.joinToString("、") { it.displayName }
}

/** 扫描所有动作的当前生效键，找出一对多冲突，按 keyCode 排序。 */
fun remoteBindingConflicts(bindings: RemoteKeyBindings): List<RemoteBindingConflict> {
    val byCode = mutableMapOf<Int, MutableList<RemoteKeyAction>>()
    RemoteKeyAction.entries.forEach { action ->
        bindings.keysFor(action).forEach { code ->
            byCode.getOrPut(code) { mutableListOf() }.add(action)
        }
    }
    return byCode.filterValues { it.size > 1 }
        .toSortedMap()
        .map { (code, actions) -> RemoteBindingConflict(code, actions.toList()) }
}

/** 按键学习向导的 6 个步骤（↑ ↓ ← → OK 返回）；scanCode 与 keyCode 同时采集。 */
object RemoteKeyLearning {
    val steps: List<Pair<RemoteKeyAction, String>> = listOf(
        RemoteKeyAction.DirectionUp to "按 ↑ 上键",
        RemoteKeyAction.DirectionDown to "按 ↓ 下键",
        RemoteKeyAction.DirectionLeft to "按 ← 左键",
        RemoteKeyAction.DirectionRight to "按 → 右键",
        RemoteKeyAction.Confirm to "按 OK 确认键",
        RemoteKeyAction.Back to "按 返回键",
    )
}

/** `remote_profile.json` 内存模型。 */
data class RemoteProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val preset: String = "custom",
    val bindings: RemoteKeyBindings = RemoteKeyBindings.Default,
    val deviceFingerprint: String = "",
    val feel: RemoteFeel = RemoteFeel(),
    val updatedAt: String = "",
) {

    /** 不含 checksum 的规范 body；导出与校验都用它，保证同一份输入得到同一摘要。 */
    fun bodyJson(): JsonObject = buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("preset", preset)
        put("deviceFingerprint", deviceFingerprint)
        putJsonObject("bindings") {
            putJsonObject("keys") {
                RemoteKeyAction.entries.forEach { action ->
                    putJsonArray(action.id) { bindings.keysFor(action).sorted().forEach { add(JsonPrimitive(it)) } }
                }
            }
            putJsonObject("custom") {
                bindings.custom.forEach { (id, codes) ->
                    putJsonArray(id) { codes.sorted().forEach { add(JsonPrimitive(it)) } }
                }
            }
            putJsonObject("removed") {
                bindings.removed.forEach { (id, codes) ->
                    putJsonArray(id) { codes.sorted().forEach { add(JsonPrimitive(it)) } }
                }
            }
            putJsonObject("scanCodes") {
                bindings.scanCodes.forEach { (id, codes) ->
                    putJsonArray(id) { codes.sorted().forEach { add(JsonPrimitive(it)) } }
                }
            }
            putJsonObject("deviceKeys") {
                bindings.deviceKeys.forEach { (deviceId, byAction) ->
                    putJsonObject(deviceId) {
                        byAction.forEach { (id, codes) ->
                            putJsonArray(id) { codes.sorted().forEach { add(JsonPrimitive(it)) } }
                        }
                    }
                }
            }
        }
        putJsonObject("feel") {
            put("seekStepMs", feel.seekStepMs)
            put("seekCurve", feel.seekCurve)
            put("longPressMs", feel.longPressMs)
            put("debounceCrossKeyMs", feel.debounceCrossKeyMs)
            put("autoRepeatCap", feel.autoRepeatCap)
            put("directTuneTimeoutMs", feel.directTuneTimeoutMs)
            put("focusRingDp", feel.focusRingDp)
            put("listScroll", feel.listScroll)
            put("announceLevel", feel.announceLevel)
        }
        put("updatedAt", updatedAt)
    }

    /** 导出：规范 body + `checksum` = "sha256:<hex>"。 */
    fun toJson(): String {
        val body = bodyJson()
        return buildJsonObject {
            body.entries.forEach { (key, value) -> put(key, value) }
            put("checksum", "sha256:${sha256(body.toString())}")
        }.toString()
    }

    companion object {
        const val SCHEMA_VERSION = 2

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun fromBindings(
            bindings: RemoteKeyBindings,
            deviceFingerprint: String = "",
            updatedAt: String = "",
        ): RemoteProfile {
            val preset = RemotePreset.fromId(bindings.preset)
            return RemoteProfile(
                schemaVersion = SCHEMA_VERSION,
                preset = bindings.preset,
                bindings = bindings,
                deviceFingerprint = deviceFingerprint,
                feel = preset?.feel ?: RemoteFeel(),
                updatedAt = updatedAt,
            )
        }

        data class ParseResult(val profile: RemoteProfile?, val error: String?)

        /** 导入：先验 checksum 与版本，再逐字段安全解析；任何坏片段都不抛异常。 */
        fun parse(raw: String): ParseResult {
            val root = try {
                json.parseToJsonElement(raw.trim()) as? JsonObject
                    ?: return ParseResult(null, "遥控器配置根节点不是对象")
            } catch (_: Exception) {
                return ParseResult(null, "遥控器配置 JSON 解析失败")
            }
            val version = (root["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
            if (version > SCHEMA_VERSION) {
                return ParseResult(null, "遥控器配置版本 $version 高于当前支持的 $SCHEMA_VERSION，请先升级应用")
            }
            val expected = (root["checksum"] as? JsonPrimitive)?.content?.trim().orEmpty()
                .removePrefix("sha256:").trim()
            if (expected.isNotEmpty()) {
                val body = buildJsonObject {
                    root.entries.forEach { (key, value) -> if (key != "checksum") put(key, value) }
                }
                if (!sha256(body.toString()).equals(expected, ignoreCase = true)) {
                    return ParseResult(null, "遥控器配置校验失败：文件内容已损坏或被修改，请勿导入")
                }
            }

            val bindingsObj = root["bindings"] as? JsonObject
            val custom = intMap(bindingsObj?.get("custom"))
            // schemaVersion 1 的 "keys" 是追加语义的用户键，按 custom 导入即可保留用户意图。
            if (custom.isEmpty()) intMap(bindingsObj?.get("keys")).forEach { (id, codes) ->
                RemoteKeyAction.fromId(id)?.let { custom[it.id] = codes }
            }
            val removed = intMap(bindingsObj?.get("removed"))
            val scanCodes = intMap(bindingsObj?.get("scanCodes"))
            val deviceKeys = mutableMapOf<String, Map<String, Set<Int>>>()
            (bindingsObj?.get("deviceKeys") as? JsonObject)?.forEach { (deviceId, element) ->
                intMap(element).takeIf { it.isNotEmpty() }?.let { deviceKeys[deviceId] = it }
            }
            val preset = (root["preset"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { "custom" }
            val bindings = RemoteKeyBindings(
                custom = custom.filterKeys { RemoteKeyAction.fromId(it) != null },
                removed = removed.filterKeys { RemoteKeyAction.fromId(it) != null },
                scanCodes = scanCodes.filterKeys { RemoteKeyAction.fromId(it) != null },
                deviceKeys = deviceKeys,
                preset = preset,
            )
            val feelObj = root["feel"] as? JsonObject
            val feel = RemoteFeel(
                seekStepMs = long(feelObj?.get("seekStepMs"), RemoteFeel().seekStepMs),
                seekCurve = string(feelObj?.get("seekCurve"), RemoteFeel().seekCurve),
                longPressMs = int(feelObj?.get("longPressMs"), RemoteFeel().longPressMs),
                debounceCrossKeyMs = int(feelObj?.get("debounceCrossKeyMs"), RemoteFeel().debounceCrossKeyMs),
                autoRepeatCap = int(feelObj?.get("autoRepeatCap"), RemoteFeel().autoRepeatCap),
                directTuneTimeoutMs = int(feelObj?.get("directTuneTimeoutMs"), RemoteFeel().directTuneTimeoutMs),
                focusRingDp = int(feelObj?.get("focusRingDp"), RemoteFeel().focusRingDp),
                listScroll = string(feelObj?.get("listScroll"), RemoteFeel().listScroll),
                announceLevel = string(feelObj?.get("announceLevel"), RemoteFeel().announceLevel),
            )
            return ParseResult(
                RemoteProfile(
                    schemaVersion = version.coerceAtLeast(1),
                    preset = preset,
                    bindings = bindings,
                    deviceFingerprint = string(root["deviceFingerprint"], ""),
                    feel = feel,
                    updatedAt = string(root["updatedAt"], ""),
                ),
                null,
            )
        }

        private fun intMap(element: JsonElement?): MutableMap<String, Set<Int>> {
            val result = mutableMapOf<String, Set<Int>>()
            (element as? JsonObject)?.forEach { (key, value) ->
                (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                    ?.toSet()?.takeIf { it.isNotEmpty() }?.let { result[key] = it }
            }
            return result
        }

        private fun string(element: JsonElement?, fallback: String): String =
            (element as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

        private fun int(element: JsonElement?, fallback: Int): Int =
            (element as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

        private fun long(element: JsonElement?, fallback: Long): Long =
            (element as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback
    }
}
