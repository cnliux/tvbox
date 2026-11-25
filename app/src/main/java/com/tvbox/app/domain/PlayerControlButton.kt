package com.tvbox.app.domain

/**
 * 播放器底栏「可显隐」的内置功能按钮（任务6 播放器按钮可配置）。
 *
 * 播放/暂停、上/下一集（台）、返回等核心控件始终显示，不在此列——隐藏它们会让播放器
 * 失去基本可用性。仅把「锦上添花」的功能入口开放给用户增删，默认全部显示，与既有行为一致。
 *
 * [storageKey] 用于持久化用户隐藏集合；[capabilityId] 与 PlayerChrome 的
 * [com.tvbox.app.ui.PlayerChromeCapabilities] 字段一一对应，作为「该页是否具备此能力」的判据。
 * 按钮最终显示 = 具备该能力 AND 用户未隐藏。
 */
enum class PlayerControlButton(val storageKey: String, val displayName: String) {
    Cast("cast", "投屏"),
    Episodes("episodes", "选集"),
    Sources("sources", "线路(选源)"),
    Lines("lines", "线路(平台)"),
    Favorite("favorite", "收藏"),
    Speed("speed", "倍速"),
    AudioTracks("audio_tracks", "音轨"),
    SubtitleTracks("subtitle_tracks", "字幕"),
    Resize("resize", "画面"),
    Skip("skip", "跳过"),
    Info("info", "信息"),
    Url("url", "地址"),
    Settings("settings", "设置"),
    ;

    companion object {
        fun fromStorageKey(value: String): PlayerControlButton? =
            entries.firstOrNull { it.storageKey == value }
    }
}

/**
 * 判断某个能力对应的按钮是否应当显示：
 * 具备该能力且用户未在设置里隐藏它。
 */
fun isPlayerControlVisible(
    hiddenControls: Set<String>,
    button: PlayerControlButton,
): Boolean = button.storageKey !in hiddenControls
