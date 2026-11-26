package com.tvbox.app.domain

import android.view.KeyEvent

/**
 * 统一遥控键位（对应「遥控器快捷键统一 + 支持自定义」需求）。
 *
 * 设计：把播放器三套键位路由（点播 / 电视直播 / 平台直播）中重复的语义动作
 * 收敛成一张「语义动作 → 物理键码」绑定表，由 [com.tvbox.app.ui.UnifiedRemoteKeyRouter] 消费。
 *
 * - 默认绑定 = 现有硬编码行为，保证不改变默认体验；
 * - 用户可在设置里为某个语义动作改绑物理键（覆盖表），custom 非空时整体替换默认，
 *   保证用户改绑后行为立即生效且可预期（不再追加默认键）。
 *
 * P1-09：在同一张表里附带按键学习数据（scanCode / deviceId+keyCode / tombstone），
 * 使同一 keyCode 的不同物理键（OK 与 PLAY_PAUSE 同码的魔盒）也能被区分，
 * 且冲突可视化时可以把某个物理键从其它动作上摘掉。
 *
 * 电视直播的数字选台、平台直播的候选浏览等页面特有动作不进绑定表，仍由各页保留。
 */

/** 可自定义的遥控语义动作（跨播放页通用的高频动作）。 */
enum class RemoteKeyAction(val id: String, val displayName: String) {
    // ── 基础动作：三个播放页通用 ──────────────────────────────────────────
    /** 播放 / 暂停切换（点播用得多；直播页由页面决定是否处理）。 */
    PlayPause("play_pause", "播放 / 暂停"),

    /** 定向播放键（MEDIA_PLAY 126）：明确要播，已播放时 no-op（P2-12）。 */
    Play("play", "播放（定向）"),

    /** 定向暂停键（MEDIA_PAUSE 127）：明确要停，已暂停时 no-op（P2-12）。 */
    Pause("pause", "暂停（定向）"),

    /** 方向键左（点播=快退、电视直播=换台、平台直播=换房间）。 */
    DirectionLeft("direction_left", "左键"),

    /** 方向键右（点播=快进、电视直播=换台、平台直播=换房间）。 */
    DirectionRight("direction_right", "右键"),

    /** 方向键上（电视直播=换线路、平台直播=换候选）。 */
    DirectionUp("direction_up", "上键"),

    /** 方向键下（电视直播=换线路、平台直播=换候选）。 */
    DirectionDown("direction_down", "下键"),

    /** 确认键（OK / ENTER）：唤控制栏 / 确认 / 开列表。 */
    Confirm("confirm", "确认（OK）"),

    /** 上一集 / 上一台（点播=上一集，电视直播=上一台）。 */
    Previous("previous", "上一集 / 上一台"),

    /** 下一集 / 下一台。 */
    Next("next", "下一集 / 下一台"),

    /** 快退（点播专用；方向左键已覆盖，此为 MEDIA_REWIND 专用键）。 */
    SeekBack("seek_back", "快退键（REWIND）"),

    /** 快进（点播专用；MEDIA_FAST_FORWARD 专用键）。 */
    SeekForward("seek_forward", "快进键（FAST_FORWARD）"),

    /** 打开播放器设置面板。 */
    Settings("settings", "设置"),

    /** 打开播放信息面板。 */
    Info("info", "播放信息"),

    /** MENU 键：切倍速（点播）/ 重连（平台直播）/ 频道列表（电视直播）。 */
    Menu("menu", "菜单（MENU）"),

    /** BACK 键（不在播放器消费，交由浮层层级处理）。 */
    Back("back", "返回（BACK）"),

    /** 停止键：点播=暂停并保持控制栏，直播=停止并退出到列表语义。 */
    Stop("stop", "停止"),

    /** 退出键（ESC）：直播/播放页直接退出，不经过「先收控件」链路。 */
    Exit("exit", "退出（ESC）"),
    ;

    companion object {
        /** 从存储字符串解析；非法值回退 null，由调用方忽略。 */
        fun fromId(value: String?): RemoteKeyAction? = entries.firstOrNull { it.id == value }
    }
}

/**
 * 统一遥控键位绑定表：语义动作 → 物理键码集合。
 *
 * [defaultBindings] 是各动作的默认键（与既有硬编码一致）；用户自定义按「覆盖语义」处理：
 * 某动作的 custom 非空时整体替换默认（改绑立即生效且可预期），为空/缺省时才回退默认。
 *
 * P1-09 附加数据：
 * - [removed]：动作级 tombstone，「解除这个键」时不必依赖空 custom（空集语义是回退默认）；
 * - [scanCodes] / [deviceKeys]：按键学习采到的硬件指纹，用于 scanCode > deviceId+keyCode > keyCode 的优先级；
 * - [preset]：当前生效的出厂预设 id，随绑定一起导出 / 导入。
 */
data class RemoteKeyBindings(
    /** 用户自定义：动作 id → 绑定物理键码集合。非空时整体替换默认；空/缺省用默认。 */
    val custom: Map<String, Set<Int>> = emptyMap(),
    /** 动作级解绑墓碑：从该动作当前生效键集合中剔除这些键。 */
    val removed: Map<String, Set<Int>> = emptyMap(),
    /** 按键学习：动作 id → 硬件 scanCode 集合（同码键的唯一区分依据）。 */
    val scanCodes: Map<String, Set<Int>> = emptyMap(),
    /** 按键学习：deviceId → (动作 id → 该设备上报的 keyCode 集合)。 */
    val deviceKeys: Map<String, Map<String, Set<Int>>> = emptyMap(),
    /** 当前预设 id（standard / legacy6 / a11y / custom）。 */
    val preset: String = "custom",
) {

    /** 某语义动作当前绑定的全部键码：custom 非空则整体替换默认，再剔除 tombstone。 */
    fun keysFor(action: RemoteKeyAction): Set<Int> {
        val base = custom[action.id]?.takeIf { it.isNotEmpty() } ?: defaultBindings[action].orEmpty()
        return base - removed[action.id].orEmpty()
    }

    /** 反查：物理键码触发了哪些语义动作（同一键可能对应多个动作，按页面上下午裁决）。 */
    fun actionsFor(keyCode: Int): List<RemoteKeyAction> =
        RemoteKeyAction.entries.filter { keyCode in keysFor(it) }

    /** 用户为某动作追加绑定一个物理键。 */
    fun withBinding(action: RemoteKeyAction, keyCode: Int): RemoteKeyBindings {
        if (keyCode in keysFor(action)) return this
        val merged = custom[action.id].orEmpty() + keyCode
        val stillRemoved = removed[action.id].orEmpty() - keyCode
        return copy(
            custom = custom + (action.id to merged),
            removed = if (stillRemoved.isEmpty()) removed - action.id else removed + (action.id to stillRemoved),
        )
    }

    /** 清除某动作的用户自定义绑定，恢复默认（同时清除该动作的 tombstone）。 */
    fun withoutBinding(action: RemoteKeyAction): RemoteKeyBindings =
        copy(custom = custom - action.id, removed = removed - action.id)

    /**
     * 序列化为 "actionId:keyCode,actionId!removed,actionId#scanCode,actionId@deviceId=keyCode,~preset"。
     * 老格式（只有 `:` 片段）照常解析；新片段只在用到对应能力时才出现。
     */
    fun encode(): String = buildList {
        custom.forEach { (actionId, codes) -> codes.forEach { add("$actionId:$it") } }
        removed.forEach { (actionId, codes) -> codes.forEach { add("$actionId!$it") } }
        scanCodes.forEach { (actionId, codes) -> codes.forEach { add("$actionId#$it") } }
        deviceKeys.forEach { (deviceId, byAction) ->
            byAction.forEach { (actionId, codes) -> codes.forEach { add("$actionId@$deviceId=$it") } }
        }
        if (preset != "custom" && preset.isNotBlank()) add("~$preset")
    }.joinToString(",")

    companion object {
        val Default = RemoteKeyBindings()

        /** 从存储字符串解析；非法片段忽略，兼容老数据。 */
        fun decode(value: String?): RemoteKeyBindings {
            if (value.isNullOrBlank()) return Default
            val parsedCustom = mutableMapOf<String, MutableSet<Int>>()
            val parsedRemoved = mutableMapOf<String, MutableSet<Int>>()
            val parsedScan = mutableMapOf<String, MutableSet<Int>>()
            val parsedDevice = mutableMapOf<String, MutableMap<String, MutableSet<Int>>>()
            var parsedPreset = "custom"
            value.split(',').forEach { token ->
                val trimmed = token.trim()
                if (trimmed.isBlank()) return@forEach
                when {
                    trimmed.startsWith("~") -> {
                        val candidate = trimmed.drop(1)
                        if (candidate.all { it.isLetterOrDigit() || it == '_' }) parsedPreset = candidate
                    }
                    trimmed.contains('!') -> {
                        val parts = trimmed.split('!')
                        if (parts.size == 2) {
                            RemoteKeyAction.fromId(parts[0])?.let { action ->
                                parts[1].toIntOrNull()?.let {
                                    parsedRemoved.getOrPut(action.id) { mutableSetOf() }.add(it)
                                }
                            }
                        }
                    }
                    trimmed.contains('#') -> {
                        val parts = trimmed.split('#')
                        if (parts.size == 2) {
                            RemoteKeyAction.fromId(parts[0])?.let { action ->
                                parts[1].toIntOrNull()?.let {
                                    parsedScan.getOrPut(action.id) { mutableSetOf() }.add(it)
                                }
                            }
                        }
                    }
                    trimmed.contains('@') -> {
                        val parts = trimmed.split('@')
                        if (parts.size == 2) {
                            val kv = parts[1].split('=')
                            if (kv.size == 2) {
                                RemoteKeyAction.fromId(parts[0])?.let { action ->
                                    kv[1].toIntOrNull()?.let {
                                        parsedDevice.getOrPut(kv[0]) { mutableMapOf() }
                                            .getOrPut(action.id) { mutableSetOf() }.add(it)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val parts = trimmed.split(':')
                        if (parts.size == 2) {
                            RemoteKeyAction.fromId(parts[0].trim())?.let { action ->
                                parts[1].trim().toIntOrNull()?.let {
                                    parsedCustom.getOrPut(action.id) { mutableSetOf() }.add(it)
                                }
                            }
                        }
                    }
                }
            }
            return RemoteKeyBindings(
                custom = parsedCustom.mapValues { it.value.toSet() },
                removed = parsedRemoved.mapValues { it.value.toSet() },
                scanCodes = parsedScan.mapValues { it.value.toSet() },
                deviceKeys = parsedDevice.mapValues { entry -> entry.value.mapValues { it.value.toSet() } },
                preset = parsedPreset,
            )
        }

        /** 各语义动作的默认物理键码（与既有硬编码一致）。 */
        val defaultBindings: Map<RemoteKeyAction, Set<Int>> = mapOf(
            RemoteKeyAction.PlayPause to setOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            ),
            RemoteKeyAction.Play to setOf(KeyEvent.KEYCODE_MEDIA_PLAY),
            RemoteKeyAction.Pause to setOf(KeyEvent.KEYCODE_MEDIA_PAUSE),
            RemoteKeyAction.DirectionLeft to setOf(KeyEvent.KEYCODE_DPAD_LEFT),
            RemoteKeyAction.DirectionRight to setOf(KeyEvent.KEYCODE_DPAD_RIGHT),
            RemoteKeyAction.DirectionUp to setOf(KeyEvent.KEYCODE_DPAD_UP),
            RemoteKeyAction.DirectionDown to setOf(KeyEvent.KEYCODE_DPAD_DOWN),
            RemoteKeyAction.Confirm to setOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
            ),
            RemoteKeyAction.Back to setOf(KeyEvent.KEYCODE_BACK),
            RemoteKeyAction.SeekBack to setOf(KeyEvent.KEYCODE_MEDIA_REWIND),
            RemoteKeyAction.SeekForward to setOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
            RemoteKeyAction.Previous to setOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            RemoteKeyAction.Next to setOf(KeyEvent.KEYCODE_MEDIA_NEXT),
            RemoteKeyAction.Settings to setOf(KeyEvent.KEYCODE_SETTINGS),
            RemoteKeyAction.Info to setOf(KeyEvent.KEYCODE_INFO),
            RemoteKeyAction.Menu to setOf(KeyEvent.KEYCODE_MENU),
            RemoteKeyAction.Stop to setOf(KeyEvent.KEYCODE_MEDIA_STOP),
            RemoteKeyAction.Exit to setOf(KeyEvent.KEYCODE_ESCAPE),
        )
    }
}

/** 把某个物理键从指定动作上摘掉（动作级 tombstone，不影响其它动作）。 */
fun RemoteKeyBindings.withoutKey(action: RemoteKeyAction, keyCode: Int): RemoteKeyBindings {
    val remainingCustom = custom[action.id].orEmpty() - keyCode
    val nextCustom = if (remainingCustom.isEmpty()) custom - action.id else custom + (action.id to remainingCustom)
    return copy(
        custom = nextCustom,
        removed = removed + (action.id to (removed[action.id].orEmpty() + keyCode)),
    )
}

/** 冲突裁决：保留 [action] 持有 [keyCode]，把它从其它动作上摘掉；必要时把键补回保留方。 */
fun RemoteKeyBindings.keepOnly(action: RemoteKeyAction, keyCode: Int): RemoteKeyBindings {
    var result = this
    RemoteKeyAction.entries.forEach { other ->
        if (other != action && keyCode in result.keysFor(other)) {
            result = result.withoutKey(other, keyCode)
        }
    }
    if (keyCode !in result.keysFor(action)) result = result.withBinding(action, keyCode)
    return result
}

/** 按键学习写入：同时记录 scanCode 与 deviceId+keyCode。scanCode<=0 的设备不记录。 */
fun RemoteKeyBindings.learnKey(
    action: RemoteKeyAction,
    deviceId: String,
    keyCode: Int,
    scanCode: Int,
): RemoteKeyBindings {
    var result = this
    if (scanCode > 0) {
        result = result.copy(
            scanCodes = result.scanCodes + (action.id to (result.scanCodes[action.id].orEmpty() + scanCode)),
        )
    }
    if (deviceId.isNotBlank() && keyCode > 0) {
        val perDevice = result.deviceKeys[deviceId].orEmpty()
        result = result.copy(
            deviceKeys = result.deviceKeys + (
                deviceId to (perDevice + (action.id to (perDevice[action.id].orEmpty() + keyCode)))
                ),
        )
    }
    return result
}

/** 只记录 scanCode（无设备上下文时，例如某些蓝牙键盘）。 */
fun RemoteKeyBindings.withScanCode(action: RemoteKeyAction, scanCode: Int): RemoteKeyBindings =
    if (scanCode <= 0) this else copy(
        scanCodes = scanCodes + (action.id to (scanCodes[action.id].orEmpty() + scanCode)),
    )

/** 设置预设 id，保留已学习到的硬件映射。 */
fun RemoteKeyBindings.withPreset(id: String): RemoteKeyBindings = copy(preset = id)

/**
 * 强信号裁决：scanCode > deviceId+keyCode > （无强信号时返回 null，由调用方回退 keyCode 表）。
 * 只对学习过的物理键生效；未学习的遥控器行为与改动前一致。
 */
fun RemoteKeyBindings.resolveHardware(deviceId: String?, keyCode: Int, scanCode: Int): RemoteKeyAction? {
    val byScan = if (scanCode > 0) {
        RemoteKeyAction.entries.firstOrNull { scanCode in scanCodes[it.id].orEmpty() }
    } else {
        null
    }
    if (byScan != null) return byScan
    if (deviceId.isNullOrBlank()) return null
    return RemoteKeyAction.entries.firstOrNull { keyCode in deviceKeys[deviceId]?.get(it.id).orEmpty() }
}

/** 完整裁决：强信号优先，否则回退 keyCode 绑定表（枚举序为首的命中动作）。 */
fun RemoteKeyBindings.resolve(deviceId: String?, keyCode: Int, scanCode: Int): RemoteKeyAction? =
    resolveHardware(deviceId, keyCode, scanCode) ?: actionsFor(keyCode).firstOrNull()
