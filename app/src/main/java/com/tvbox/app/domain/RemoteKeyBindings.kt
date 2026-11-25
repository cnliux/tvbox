package com.tvbox.app.domain

import android.view.KeyEvent

/**
 * 统一遥控键位（对应「遥控器快捷键统一 + 支持自定义」需求）。
 *
 * 设计：把播放器三套键位路由（点播 / 电视直播 / 平台直播）中重复的语义动作
 * 收敛成一张「语义动作 → 物理键码」绑定表，由 [com.tvbox.app.ui.UnifiedRemoteKeyRouter] 消费。
 *
 * - 默认绑定 = 现有硬编码行为，保证不改变默认体验；
 * - 用户可在设置里为某个语义动作改绑物理键（覆盖表），默认键仍可用（不排他），
 *   避免用户改绑后把常用键弄丢。
 *
 * 电视直播的数字选台、平台直播的候选浏览等页面特有动作不进绑定表，仍由各页保留。
 */

/** 可自定义的遥控语义动作（跨播放页通用的高频动作）。 */
enum class RemoteKeyAction(val id: String, val displayName: String) {
    // ── 基础动作：三个播放页通用 ──────────────────────────────────────────
    /** 播放 / 暂停切换（点播用得多；直播页由页面决定是否处理）。 */
    PlayPause("play_pause", "播放 / 暂停"),

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
    ;

    companion object {
        /** 从存储字符串解析；非法值回退 null，由调用方忽略。 */
        fun fromId(value: String?): RemoteKeyAction? = entries.firstOrNull { it.id == value }
    }
}

/**
 * 统一遥控键位绑定表：语义动作 → 物理键码集合。
 *
 * [defaultBindings] 是各动作的默认键（与既有硬编码一致）；用户自定义只存「额外增加」的键，
 * 查询时 default + custom 合并，保证改绑不丢默认键。
 */
data class RemoteKeyBindings(
    /** 用户自定义：动作 id → 附加物理键码集合。空表示全部用默认。 */
    val custom: Map<String, Set<Int>> = emptyMap(),
) {

    /** 某语义动作当前绑定的全部键码（默认 + 用户附加）。 */
    fun keysFor(action: RemoteKeyAction): Set<Int> =
        (defaultBindings[action].orEmpty()) + (custom[action.id].orEmpty())

    /** 反查：物理键码触发了哪些语义动作（同一键可能对应多个动作，按页面上下午裁决）。 */
    fun actionsFor(keyCode: Int): List<RemoteKeyAction> =
        RemoteKeyAction.entries.filter { keyCode in keysFor(it) }

    /** 用户为某动作追加绑定一个物理键。 */
    fun withBinding(action: RemoteKeyAction, keyCode: Int): RemoteKeyBindings {
        if (keyCode in defaultBindings[action].orEmpty()) return this
        val merged = custom[action.id].orEmpty() + keyCode
        return copy(custom = custom + (action.id to merged))
    }

    /** 清除某动作的用户自定义绑定，恢复默认。 */
    fun withoutBinding(action: RemoteKeyAction): RemoteKeyBindings =
        copy(custom = custom - action.id)

    /** 序列化为 "actionId:keyCode,actionId:keyCode" 便于偏好存储。 */
    fun encode(): String =
        custom.entries
            .flatMap { (actionId, codes) -> codes.map { "$actionId:$it" } }
            .joinToString(",")

    companion object {
        val Default = RemoteKeyBindings()

        /** 从存储字符串解析；非法片段忽略，兼容老数据。 */
        fun decode(value: String?): RemoteKeyBindings {
            if (value.isNullOrBlank()) return Default
            val parsed = mutableMapOf<String, MutableSet<Int>>()
            value.split(',').forEach { token ->
                val parts = token.split(':')
                if (parts.size != 2) return@forEach
                val action = RemoteKeyAction.fromId(parts[0].trim()) ?: return@forEach
                val code = parts[1].trim().toIntOrNull() ?: return@forEach
                parsed.getOrPut(action.id) { mutableSetOf() }.add(code)
            }
            return RemoteKeyBindings(custom = parsed.mapValues { it.value.toSet() })
        }

        /** 各语义动作的默认物理键码（与既有硬编码一致）。 */
        val defaultBindings: Map<RemoteKeyAction, Set<Int>> = mapOf(
            RemoteKeyAction.PlayPause to setOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
            ),
            RemoteKeyAction.SeekBack to setOf(KeyEvent.KEYCODE_MEDIA_REWIND),
            RemoteKeyAction.SeekForward to setOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
            RemoteKeyAction.Previous to setOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            RemoteKeyAction.Next to setOf(KeyEvent.KEYCODE_MEDIA_NEXT),
            RemoteKeyAction.Settings to setOf(KeyEvent.KEYCODE_SETTINGS),
            RemoteKeyAction.Info to setOf(KeyEvent.KEYCODE_INFO),
            RemoteKeyAction.Menu to setOf(KeyEvent.KEYCODE_MENU),
        )
    }
}
