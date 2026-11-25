package com.tvbox.app.domain

/**
 * 播放器底栏按「播放场景」分治（任务：三类播放器按钮重建）。
 *
 * 背景：此前 `playerHiddenControls` 是一份全局隐藏集合，用户为了在点播页少看一个按钮，
 * 会把电视直播/平台直播页的同名按钮一起隐藏；反过来直播页需要的按钮又没有独立开关。
 * 现在把按钮清单收口到本文件：**每种场景一张默认清单 + 一份场景独立的隐藏集合**，
 * 页面只声明「本页具备哪些能力」，共享层用本策略决定「实际渲染哪些按钮」。
 *
 * 核心控件（播放/暂停、上一集/下一台/下一个、返回）不在此列，任何场景都保留。
 */
enum class PlayerSurface(val storageKey: String, val label: String) {
    Vod("vod", "点播"),
    LiveTv("live_tv", "电视直播"),
    PlatformLive("platform_live", "平台直播"),
    ;

    companion object {
        fun fromStorageKey(value: String): PlayerSurface? =
            entries.firstOrNull { it.storageKey == value }
    }
}

object PlayerControlLayout {
    /**
     * 每种场景的默认按钮（顺序即渲染顺序）。
     * 只列「锦上添花」的功能入口；核心控件由共享层固定渲染。
     */
    val defaults: Map<PlayerSurface, List<PlayerControlButton>> = mapOf(
        // 点播：集数/源/倍速/音轨字幕是主路径，跳过片头尾与信息地址次之。
        PlayerSurface.Vod to listOf(
            PlayerControlButton.Cast,
            PlayerControlButton.Episodes,
            PlayerControlButton.Sources,
            PlayerControlButton.Speed,
            PlayerControlButton.AudioTracks,
            PlayerControlButton.SubtitleTracks,
            PlayerControlButton.Resize,
            PlayerControlButton.Skip,
            PlayerControlButton.Info,
            PlayerControlButton.Url,
        ),
        // 电视直播：直播不可 seek / 无倍速，重点是换台、换线、收藏与画面。
        PlayerSurface.LiveTv to listOf(
            PlayerControlButton.Favorite,
            PlayerControlButton.Lines,
            PlayerControlButton.Resize,
            PlayerControlButton.Info,
            PlayerControlButton.Url,
        ),
        // 平台直播：房间切换由核心「上一个/下一个」承担，调试/重连档位由页面 actions 注入。
        PlayerSurface.PlatformLive to listOf(
            PlayerControlButton.Cast,
            PlayerControlButton.Lines,
            PlayerControlButton.Resize,
            PlayerControlButton.Info,
            PlayerControlButton.Url,
        ),
    )

    /** 该场景可供用户增删的按钮（=默认清单，顺序一致）。 */
    fun configurableButtons(surface: PlayerSurface): List<PlayerControlButton> =
        defaults[surface].orEmpty()

    /**
     * 实际可见按钮：场景默认清单 − 旧版全局隐藏集合 − 该场景独立隐藏集合。
     * 全局集合仅用于兼容旧用户设置；新设置一律写入场景独立集合。
     */
    fun visibleButtons(
        surface: PlayerSurface,
        hiddenGlobal: Set<String> = emptySet(),
        hiddenForSurface: Set<String> = emptySet(),
    ): List<PlayerControlButton> = configurableButtons(surface).filter { button ->
        button.storageKey !in hiddenGlobal && button.storageKey !in hiddenForSurface
    }

    fun visibleKeys(
        surface: PlayerSurface,
        hiddenGlobal: Set<String> = emptySet(),
        hiddenForSurface: Set<String> = emptySet(),
    ): Set<String> = visibleButtons(surface, hiddenGlobal, hiddenForSurface)
        .mapTo(linkedSetOf()) { it.storageKey }

    /** 把某个按钮在指定场景设为显示/隐藏，返回新的按场景隐藏表。 */
    fun setVisible(
        current: Map<String, Set<String>>,
        surface: PlayerSurface,
        button: PlayerControlButton,
        visible: Boolean,
    ): Map<String, Set<String>> {
        val key = surface.storageKey
        val hidden = current[key].orEmpty().toMutableSet()
        if (visible) hidden.remove(button.storageKey) else hidden.add(button.storageKey)
        val next = current.toMutableMap()
        if (hidden.isEmpty()) next.remove(key) else next[key] = hidden
        return next
    }

    fun isVisible(
        current: Map<String, Set<String>>,
        surface: PlayerSurface,
        button: PlayerControlButton,
    ): Boolean = button.storageKey !in current[surface.storageKey].orEmpty()
}
