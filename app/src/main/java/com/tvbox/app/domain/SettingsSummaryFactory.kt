package com.tvbox.app.domain

/**
 * 设置页当前值摘要的装配层（第 261–270 轮）。
 *
 * `SettingsIa` 定义了分组与条目结构，但条目从哪来一直没接上，导致「分组 / 摘要」
 * 只是可编译的纯数据。这里把本机 `AppSettings` 映射成条目，ViewModel 暴露整页摘要，
 * UI 无需进入子页就能显示当前值。
 */

fun settingsEntriesFrom(settings: AppSettings): List<SettingEntry> = listOf(
    SettingEntry(
        id = "player_speed",
        group = SettingGroup.Playback,
        label = "默认倍速",
        valueSummary = "${settings.playerSpeed}x",
    ),
    SettingEntry(
        id = "video_scale",
        group = SettingGroup.Playback,
        label = "画面比例",
        valueSummary = settings.videoScaleMode.name,
    ),
    SettingEntry(
        id = "skip_credits",
        group = SettingGroup.Playback,
        label = "跳过片头/片尾",
        valueSummary = skipCreditsHint(
            SkipCreditsConfig(
                enabled = settings.introSkipSeconds > 0 || settings.outroSkipSeconds > 0,
                introSeconds = settings.introSkipSeconds,
                outroSeconds = settings.outroSkipSeconds,
            )
        ),
    ),
    SettingEntry(
        id = "home_source",
        group = SettingGroup.ContentSources,
        label = "视频接口",
        valueSummary = settings.homeApiLineId.ifBlank { "未选择" } +
            "（${settings.customVideoApiLines.size} 个自定义）",
    ),
    SettingEntry(
        id = "live_sources",
        group = SettingGroup.ContentSources,
        label = "直播源",
        valueSummary = "${settings.liveSourceUrls.size} 个订阅",
    ),
    SettingEntry(
        id = "blocked",
        group = SettingGroup.ContentSources,
        label = "内容过滤",
        valueSummary = "${settings.blockedVideoCategories.size} 个分类 / ${settings.blockedSourceIds.size} 个源",
    ),
    SettingEntry(
        id = "theme",
        group = SettingGroup.Appearance,
        label = "主题",
        valueSummary = settings.theme.name,
    ),
    SettingEntry(
        id = "font_scale",
        group = SettingGroup.Appearance,
        label = "字号",
        valueSummary = settings.fontScale.name,
    ),
    SettingEntry(
        id = "device_mode",
        group = SettingGroup.DeviceUpdate,
        label = "设备模式",
        valueSummary = settings.deviceMode.name,
    ),
    SettingEntry(
        id = "ota",
        group = SettingGroup.DeviceUpdate,
        label = "启动检查更新",
        valueSummary = if (settings.checkUpdatesOnStartup) "开启" else "关闭",
    ),
    SettingEntry(
        id = "epg",
        group = SettingGroup.Advanced,
        label = "直播节目单",
        valueSummary = settings.epgUrl.ifBlank { "未设置" },
    ),
    SettingEntry(
        id = "playback_agent",
        group = SettingGroup.Advanced,
        label = "播放管家自动换线",
        valueSummary = if (settings.playbackAgentAutoSwitchEnabled) "开启" else "关闭",
    ),
)
