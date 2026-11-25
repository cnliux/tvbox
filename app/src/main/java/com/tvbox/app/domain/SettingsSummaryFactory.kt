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
        id = "custom_user_agent",
        group = SettingGroup.ContentSources,
        label = "自定义 User-Agent",
        valueSummary = settings.customUserAgent.ifBlank { "默认（TVBox-Android）" },
    ),
    SettingEntry(
        id = "source_health_check",
        group = SettingGroup.ContentSources,
        label = "视频源健康管理",
        valueSummary = if (settings.sourceHealthCheckEnabled) "开启（每 30 分钟后台体检）" else "关闭",
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
        id = "update_channel",
        group = SettingGroup.DeviceUpdate,
        label = "更新渠道",
        valueSummary = settings.updateChannel.displayName,
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
    // #277 切台横幅时长：0 未配置时显示「自动」，否则显示秒数。
    SettingEntry(
        id = "live_info_banner_duration",
        group = SettingGroup.Playback,
        label = "切台横幅时长",
        valueSummary = if (settings.liveInfoBannerDurationMs == LiveInfoBannerDuration.Auto) {
            "自动（电视 4s / 手机 2s）"
        } else {
            "${settings.liveInfoBannerDurationMs / 1000}s"
        },
    ),
    // #54/#266 硬解白名单：空表示未启用，非空显示配置前缀。
    SettingEntry(
        id = "decoder_device_whitelist",
        group = SettingGroup.Playback,
        label = "硬解设备白名单",
        valueSummary = settings.decoderDeviceWhitelist.ifBlank { "未启用" },
    ),
    // #94 单源超时：显示秒数。
    SettingEntry(
        id = "per_source_timeout",
        group = SettingGroup.Advanced,
        label = "单源超时",
        valueSummary = "${settings.perSourceTimeoutMs / 1000}s",
    ),
    // #318 历史隐私模式。
    SettingEntry(
        id = "history_privacy_mode",
        group = SettingGroup.Advanced,
        label = "历史隐私模式",
        valueSummary = if (settings.historyPrivacyMode) "开启（不记录新历史）" else "关闭",
    ),
    SettingEntry(
        id = "auto_next_countdown",
        group = SettingGroup.Playback,
        label = "续播倒计时",
        valueSummary = "${settings.autoNextCountdownSeconds}s",
    ),
    SettingEntry(
        id = "first_packet_timeout",
        group = SettingGroup.Playback,
        label = "首包超时",
        valueSummary = "${settings.firstPacketTimeoutMs / 1000}s",
    ),
    SettingEntry(
        id = "buffer_target",
        group = SettingGroup.Playback,
        label = "缓冲目标",
        valueSummary = "${settings.bufferTargetMs / 1000}s",
    ),
    SettingEntry(
        id = "background_playback",
        group = SettingGroup.Playback,
        label = "后台行为",
        valueSummary = when (settings.backgroundPlaybackMode) {
            "audio" -> "音频续播"
            "pip" -> "画中画"
            else -> "暂停"
        },
    ),
    SettingEntry(
        id = "gesture_lock",
        group = SettingGroup.Playback,
        label = "手势锁",
        valueSummary = if (settings.gestureLockEnabled) "开启" else "关闭",
    ),
    SettingEntry(
        id = "ua_scope",
        group = SettingGroup.ContentSources,
        label = "UA 作用域",
        valueSummary = if (settings.uaScopeMode == "scoped") "按用途分工" else "统一自定义",
    ),
    SettingEntry(
        id = "playback_manager",
        group = SettingGroup.Advanced,
        label = "播放前择优",
        valueSummary = if (settings.playbackManagerPreferHealthy) "开启" else "关闭",
    ),
    SettingEntry(
        id = "update_wifi_only",
        group = SettingGroup.DeviceUpdate,
        label = "仅 Wi-Fi 下载更新",
        valueSummary = if (settings.updateWifiOnly) "开启" else "关闭",
    ),
    SettingEntry(
        id = "ai_search",
        group = SettingGroup.Advanced,
        label = "AI 找片",
        valueSummary = if (settings.aiSearchEnabled) "开启" else "关闭",
    ),
)
