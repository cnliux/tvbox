package com.tvbox.app.data

import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.CustomVideoApiLine
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.HistoryLayout
import com.tvbox.app.domain.LiveInfoBannerDuration
import com.tvbox.app.domain.LiveRemoteDirectionMapping
import com.tvbox.app.domain.PerSourceTimeout
import com.tvbox.app.domain.PlayerBrightnessSetting
import com.tvbox.app.domain.PlayerSpeedSetting
import com.tvbox.app.domain.QuantitySetting
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.RemoteKeyBindings
import com.tvbox.app.domain.ThemeAccent
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.UpdateChannel
import com.tvbox.app.domain.UserAgentSetting
import com.tvbox.app.domain.VideoScaleMode
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 旧版 `app_settings` SharedPreferences 的原始快照。
 *
 * 值按写入时的原始类型读取（String / Int / Boolean）；缺失键为 null，类型不符也按缺失处理，
 * 供迁移逻辑做纯函数解析与单测复用，不直接依赖 Android 的 SharedPreferences。
 */
internal data class LegacySettingsSnapshot(
    val values: Map<String, Any?>,
    /** 独立文件 `files/blocked_categories.json` 的内容；文件缺失或解析失败时为 null。 */
    val blockedCategoriesFromFile: List<String>? = null,
)

/** 设置存储用到的全部 key；旧 SharedPreferences 与新 DataStore 共用同一套命名。 */
internal object SettingsStorageKeys {
    const val SCHEMA_VERSION = "schema_version"
    const val HOME_API_LINE_ID = "home_api_line_id"
    const val CUSTOM_VIDEO_API_LINES = "custom_video_api_lines"
    const val SOURCE_ORDER = "source_order"
    const val BLOCKED_SOURCE_IDS = "blocked_source_ids"
    const val LIVE_SOURCE_URLS = "live_source_urls"
    const val BLOCKED_PLATFORM_SITE_IDS = "blocked_platform_site_ids"
    const val BLOCKED_VIDEO_CATEGORIES = "blocked_video_categories"
    const val INTRO_SKIP_SECONDS = "intro_skip_seconds"
    const val OUTRO_SKIP_SECONDS = "outro_skip_seconds"
    const val HOME_CONTINUE_WATCHING = "home_continue_watching_enabled"
    const val SOURCE_SPEED_LATENCIES = "source_speed_latencies"
    const val THEME = "theme"
    const val FONT_SCALE = "font_scale"
    const val THEME_ACCENT = "theme_accent"
    const val CHECK_UPDATES_ON_STARTUP = "check_updates_on_startup"
    const val PLAYBACK_AGENT_AUTO_SWITCH = "playback_agent_auto_switch"
    const val VIDEO_SCALE_MODE = "video_scale_mode"
    const val PLAYER_SPEED = "player_speed"
    const val DECODER_STRATEGY = "decoder_strategy"
    const val LIVE_REMOTE_DIRECTION_MAPPING = "live_remote_direction_mapping"
    const val REMOTE_KEY_BINDINGS = "remote_key_bindings"
    const val CONTENT_VIDEO_SCALE_MODES = "content_video_scale_modes"
    const val CONTENT_SUBTITLE_TRACKS = "content_subtitle_tracks"
    const val DEVICE_MODE = "device_mode"
    const val LIVE_SOURCE_DEFAULT_GROUP = "live_source_default_group"
    const val MOBILE_ONBOARDED = "mobile_onboarded"
    const val LIVE_SOURCE_DEFAULT_INDEX = "live_source_default_index"
    const val EPISODE_SORT_REVERSE = "episode_sort_reverse"
    const val VIDEO_GRID_COLUMNS = "video_grid_columns"
    const val EPG_URL = "epg_url"
    const val PLATFORM_LIVE_GRID_COLUMNS = "platform_live_grid_columns"
    const val CONTINUE_WATCHING_COUNT = "continue_watching_count"
    const val EPG_CARD_ROWS = "epg_card_rows"
    const val LIVE_NUMBER_MATCH_ROWS = "live_number_match_rows"
    const val LIVE_NUMBER_SEARCH_LIMIT = "live_number_search_limit"
    const val RELATED_CHANNEL_COUNT = "related_channel_count"
    const val HOT_WORD_COUNT = "hot_word_count"
    const val SEARCH_SUGGESTION_COUNT = "search_suggestion_count"
    const val HOME_HOT_CONTENT_COUNT = "home_hot_content_count"
    const val DOUBAN_HOT_PAGE_SIZE = "douban_hot_page_size"
    const val DOUBAN_HOT_FALLBACK_LINES = "douban_hot_fallback_lines"
    const val HISTORY_MAX_ITEMS = "history_max_items"
    const val MAX_PARALLEL_SOURCE_REQUESTS = "max_parallel_source_requests"
    const val PLAYER_LOG_ENABLED = "player_log_enabled"
    const val UPDATE_CHANNEL = "update_channel"
    // #277 / #94 / #318 / #54-#266：本轮 B 档新增的四项设置。
    const val LIVE_INFO_BANNER_DURATION_MS = "live_info_banner_duration_ms"
    const val PER_SOURCE_TIMEOUT_MS = "per_source_timeout_ms"
    const val HISTORY_PRIVACY_MODE = "history_privacy_mode"
    const val DECODER_DEVICE_WHITELIST = "decoder_device_whitelist"
    // 自定义 User-Agent 与视频源健康管理（本轮新增）。
    const val CUSTOM_USER_AGENT = "custom_user_agent"
    const val SOURCE_HEALTH_CHECK_ENABLED = "source_health_check_enabled"
    // 设置页对照参考稿新增项：画面亮度 / 自动下一集 / 播放手势 / 快进步长 / 历史布局 / 封面角标 / 崩溃只本地。
    const val PLAYER_BRIGHTNESS = "player_brightness"
    const val AUTO_NEXT_EPISODE = "auto_next_episode"
    const val PLAYER_GESTURES = "player_gestures"
    const val FAST_SEEK_STEP = "fast_seek_step"
    const val HISTORY_LAYOUT = "history_layout"
    const val HISTORY_COVER_BADGE = "history_cover_badge"
    const val CRASH_LOG_LOCAL_ONLY = "crash_log_local_only"
    // 源失败熔断（连续超时自动休眠，对照设置页方案）。
    const val SOURCE_CIRCUIT_BREAKER = "source_circuit_breaker"
    // 手机端设置中心 v2 新增键。
    const val AUTO_NEXT_COUNTDOWN = "auto_next_countdown"
    const val FIRST_PACKET_TIMEOUT_MS = "first_packet_timeout_ms"
    const val RECONNECT_ATTEMPTS = "reconnect_attempts"
    const val BUFFER_TARGET_MS = "buffer_target_ms"
    const val BACKGROUND_PLAYBACK_MODE = "background_playback_mode"
    const val GESTURE_LEFT_BRIGHTNESS = "gesture_left_brightness"
    const val GESTURE_RIGHT_VOLUME = "gesture_right_volume"
    const val GESTURE_HORIZONTAL_SEEK = "gesture_horizontal_seek"
    const val GESTURE_DOUBLE_TAP_SIDE = "gesture_double_tap_side"
    const val GESTURE_DOUBLE_TAP_CENTER = "gesture_double_tap_center"
    const val GESTURE_LONG_PRESS_SPEED = "gesture_long_press_speed"
    const val GESTURE_PINCH_ZOOM = "gesture_pinch_zoom"
    const val GESTURE_TOP_SWIPE_BACK = "gesture_top_swipe_back"
    const val GESTURE_SENSITIVITY = "gesture_sensitivity"
    const val GESTURE_EDGE_GUARD = "gesture_edge_guard"
    const val GESTURE_LOCK = "gesture_lock"
    const val AUDIO_OUTPUT_MODE = "audio_output_mode"
    const val PLAYER_HIDDEN_CONTROLS = "player_hidden_controls"
    const val UA_SCOPE_MODE = "ua_scope_mode"
    const val UA_FOR_API = "ua_for_api"
    const val UA_FOR_SPIDER = "ua_for_spider"
    const val UA_FOR_LIVE = "ua_for_live"
    const val UA_FOR_EPG = "ua_for_epg"
    const val UA_FOR_PLAYBACK = "ua_for_playback"
    const val UA_FOR_WEBVIEW = "ua_for_webview"
    const val UA_EXPORT_REDACTED = "ua_export_redacted"
    const val PLAYBACK_MANAGER_PREFER_HEALTHY = "playback_manager_prefer_healthy"
    const val PLAYBACK_MANAGER_MANUAL_BUTTON = "playback_manager_manual_button"
    const val SEEK_COOLING_MS = "seek_cooling_ms"
    const val MANUAL_SOURCE_LOCK_MINUTES = "manual_source_lock_minutes"
    const val EPG_CACHE_HOURS = "epg_cache_hours"
    const val EPG_TIMEZONE = "epg_timezone"
    const val EPG_OUT_OF_RANGE_POLICY = "epg_out_of_range_policy"
    const val PLATFORM_DEFAULT_QUALITY = "platform_default_quality"
    const val FLV_PROXY_MODE = "flv_proxy_mode"
    const val PLATFORM_THIRD_PARTY_PROXY_ENABLED = "platform_third_party_proxy_enabled"
    const val PLATFORM_THIRD_PARTY_PROXY_TEMPLATES = "platform_third_party_proxy_templates"
    const val LAN_RESOLVER_ENABLED = "lan_resolver_enabled"
    const val LAN_RESOLVER_CONFIRMED = "lan_resolver_confirmed"
    const val AI_SEARCH_ENABLED = "ai_search_enabled"
    const val VOICE_SEARCH_ENABLED = "voice_search_enabled"
    const val AI_TEXT_REDACTION = "ai_text_redaction"
    const val AI_ONLY_TITLE_YEAR = "ai_only_title_year"
    const val AI_MISS_BEHAVIOR = "ai_miss_behavior"
    const val AI_RESULT_CACHE_MINUTES = "ai_result_cache_minutes"
    const val UPDATE_WIFI_ONLY = "update_wifi_only"
    const val UPDATE_EXPORT_BEFORE_INSTALL = "update_export_before_install"
    const val UPDATE_ROLLBACK_HOURS = "update_rollback_hours"
    const val UPDATE_STRONG_REMINDER_ONLY = "update_strong_reminder_only"
    const val CONTINUE_WATCHING_MAX_ITEMS = "continue_watching_max_items"
    const val HISTORY_AUTO_CLEANUP = "history_auto_cleanup"
    const val RESUME_CONFLICT_POLICY = "resume_conflict_policy"
}

/** 设置文档的 schema 版本。旧 SharedPreferences 无版本，视为 [LEGACY]；迁移后统一写入 [CURRENT]。 */
internal object SettingsSchema {
    const val LEGACY = 0
    const val CURRENT = 1
}

internal val legacySettingsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * 把旧快照解析成 [AppSettings]。
 *
 * 解析失败不抛异常：JSON 解码失败回退空列表，枚举/数量值非法回退默认值，
 * 并通过 [onWarning] 上报（生产侧接 `Log.w`，单测可忽略）。
 */
internal fun readLegacySettings(
    snapshot: LegacySettingsSnapshot,
    json: Json = legacySettingsJson,
    onWarning: (String, Throwable) -> Unit = { _, _ -> },
): AppSettings {
    val values = snapshot.values
    fun string(key: String, default: String): String = (values[key] as? String) ?: default
    fun int(key: String, default: Int): Int = (values[key] as? Int) ?: default
    fun long(key: String, default: Long): Long = (values[key] as? Long) ?: default
    fun float(key: String, default: Float): Float = (values[key] as? Float) ?: default
    fun boolean(key: String, default: Boolean): Boolean = (values[key] as? Boolean) ?: default
    fun quantity(key: String, setting: QuantitySetting): Int = setting.clamp(int(key, setting.default))
    fun stringList(key: String): List<String> =
        decodeLegacyStringList(string(key, ""), key, json, onWarning)
    fun stringMap(key: String): Map<String, String> =
        decodeLegacyStringMap(string(key, ""), key, json, onWarning)

    return AppSettings(
        homeApiLineId = string(SettingsStorageKeys.HOME_API_LINE_ID, AppSettings().homeApiLineId),
        customVideoApiLines = decodeLegacyCustomVideoApiLines(
            string(SettingsStorageKeys.CUSTOM_VIDEO_API_LINES, ""),
            json,
            onWarning,
        ),
        sourceOrder = stringList(SettingsStorageKeys.SOURCE_ORDER),
        blockedSourceIds = stringList(SettingsStorageKeys.BLOCKED_SOURCE_IDS),
        liveSourceUrls = stringList(SettingsStorageKeys.LIVE_SOURCE_URLS),
        blockedPlatformSiteIds = stringList(SettingsStorageKeys.BLOCKED_PLATFORM_SITE_IDS),
        blockedVideoCategories = snapshot.blockedCategoriesFromFile
            ?: stringList(SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES),
        introSkipSeconds = int(SettingsStorageKeys.INTRO_SKIP_SECONDS, 0),
        outroSkipSeconds = int(SettingsStorageKeys.OUTRO_SKIP_SECONDS, 0),
        homeContinueWatchingEnabled = boolean(SettingsStorageKeys.HOME_CONTINUE_WATCHING, false),
        fontScale = TvFontScale.fromStorageKey(string(SettingsStorageKeys.FONT_SCALE, TvFontScale.Normal.storageKey)),
        themeAccent = ThemeAccent.fromStorageKey(string(SettingsStorageKeys.THEME_ACCENT, ThemeAccent.Default.storageKey)),
        checkUpdatesOnStartup = boolean(SettingsStorageKeys.CHECK_UPDATES_ON_STARTUP, true),
        playbackAgentAutoSwitchEnabled = boolean(SettingsStorageKeys.PLAYBACK_AGENT_AUTO_SWITCH, true),
        videoScaleMode = VideoScaleMode.fromStorageKey(string(SettingsStorageKeys.VIDEO_SCALE_MODE, "")),
        decoderStrategy = DecoderStrategy.fromStorageKey(
            string(SettingsStorageKeys.DECODER_STRATEGY, DecoderStrategy.Auto.storageKey),
        ),
        playerSpeed = PlayerSpeedSetting.clamp(
            float(SettingsStorageKeys.PLAYER_SPEED, PlayerSpeedSetting.Default),
        ),
        contentVideoScaleModes = stringMap(SettingsStorageKeys.CONTENT_VIDEO_SCALE_MODES),
        contentSubtitleTracks = stringMap(SettingsStorageKeys.CONTENT_SUBTITLE_TRACKS),
        deviceMode = DeviceMode.fromStorageKey(string(SettingsStorageKeys.DEVICE_MODE, "")),
        liveSourceDefaultGroup = string(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_GROUP, ""),
        mobileOnboarded = boolean(SettingsStorageKeys.MOBILE_ONBOARDED, false),
        liveSourceDefaultIndex = int(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_INDEX, -1),
        episodeSortReverse = boolean(SettingsStorageKeys.EPISODE_SORT_REVERSE, false),
        epgUrl = string(SettingsStorageKeys.EPG_URL, ""),
        epgCardRows = quantity(SettingsStorageKeys.EPG_CARD_ROWS, QuantitySettings.EpgCardRows),
        liveNumberMatchRows = quantity(SettingsStorageKeys.LIVE_NUMBER_MATCH_ROWS, QuantitySettings.LiveNumberMatchRows),
        liveNumberSearchLimit = quantity(SettingsStorageKeys.LIVE_NUMBER_SEARCH_LIMIT, QuantitySettings.LiveNumberSearchLimit),
        relatedChannelCount = quantity(SettingsStorageKeys.RELATED_CHANNEL_COUNT, QuantitySettings.RelatedChannelCount),
        hotWordCount = quantity(SettingsStorageKeys.HOT_WORD_COUNT, QuantitySettings.HotWordCount),
        searchSuggestionCount = quantity(SettingsStorageKeys.SEARCH_SUGGESTION_COUNT, QuantitySettings.SearchSuggestionCount),
        doubanHotPageSize = quantity(SettingsStorageKeys.DOUBAN_HOT_PAGE_SIZE, QuantitySettings.DoubanHotPageSize),
        doubanHotFallbackLines = quantity(SettingsStorageKeys.DOUBAN_HOT_FALLBACK_LINES, QuantitySettings.DoubanHotFallbackLines),
        historyMaxItems = quantity(SettingsStorageKeys.HISTORY_MAX_ITEMS, QuantitySettings.HistoryMaxItems),
        maxParallelSourceRequests = quantity(
            SettingsStorageKeys.MAX_PARALLEL_SOURCE_REQUESTS,
            QuantitySettings.MaxParallelSourceRequests,
        ),
        playerLogEnabled = boolean(SettingsStorageKeys.PLAYER_LOG_ENABLED, false),
        updateChannel = UpdateChannel.fromStorageKey(
            string(SettingsStorageKeys.UPDATE_CHANNEL, UpdateChannel.Stable.storageKey),
        ),
        liveInfoBannerDurationMs = LiveInfoBannerDuration.clamp(
            long(SettingsStorageKeys.LIVE_INFO_BANNER_DURATION_MS, LiveInfoBannerDuration.Auto),
        ),
        perSourceTimeoutMs = PerSourceTimeout.clamp(
            long(SettingsStorageKeys.PER_SOURCE_TIMEOUT_MS, PerSourceTimeout.DefaultMs),
        ),
        historyPrivacyMode = boolean(SettingsStorageKeys.HISTORY_PRIVACY_MODE, false),
        decoderDeviceWhitelist = string(SettingsStorageKeys.DECODER_DEVICE_WHITELIST, ""),
        customUserAgent = UserAgentSetting.sanitize(
            string(SettingsStorageKeys.CUSTOM_USER_AGENT, ""),
        ),
        sourceHealthCheckEnabled = boolean(SettingsStorageKeys.SOURCE_HEALTH_CHECK_ENABLED, true),
        sourceCircuitBreakerEnabled = boolean(SettingsStorageKeys.SOURCE_CIRCUIT_BREAKER, true),
        playerBrightness = PlayerBrightnessSetting.clamp(
            int(SettingsStorageKeys.PLAYER_BRIGHTNESS, PlayerBrightnessSetting.Default),
        ),
        autoNextEpisodeEnabled = boolean(SettingsStorageKeys.AUTO_NEXT_EPISODE, true),
        playerGesturesEnabled = boolean(SettingsStorageKeys.PLAYER_GESTURES, true),
        fastSeekStepSeconds = FastSeekSetting.clamp(
            int(SettingsStorageKeys.FAST_SEEK_STEP, FastSeekSetting.DefaultStepSeconds),
        ),
        historyLayout = HistoryLayout.fromStorageKey(
            string(SettingsStorageKeys.HISTORY_LAYOUT, HistoryLayout.Card.storageKey),
        ),
        historyCoverBadgeEnabled = boolean(SettingsStorageKeys.HISTORY_COVER_BADGE, true),
        autoNextCountdownSeconds = int(SettingsStorageKeys.AUTO_NEXT_COUNTDOWN, 5),
        firstPacketTimeoutMs = long(SettingsStorageKeys.FIRST_PACKET_TIMEOUT_MS, 8_000L),
        reconnectAttempts = int(SettingsStorageKeys.RECONNECT_ATTEMPTS, 3),
        bufferTargetMs = long(SettingsStorageKeys.BUFFER_TARGET_MS, 15_000L),
        backgroundPlaybackMode = string(SettingsStorageKeys.BACKGROUND_PLAYBACK_MODE, "pause"),
        liveRemoteDirectionMapping = LiveRemoteDirectionMapping.fromStorageKey(
            string(SettingsStorageKeys.LIVE_REMOTE_DIRECTION_MAPPING, LiveRemoteDirectionMapping.Default.name),
        ),
        remoteKeyBindings = RemoteKeyBindings.decode(
            string(SettingsStorageKeys.REMOTE_KEY_BINDINGS, ""),
        ),
        gestureLeftBrightnessEnabled = boolean(SettingsStorageKeys.GESTURE_LEFT_BRIGHTNESS, true),
        gestureRightVolumeEnabled = boolean(SettingsStorageKeys.GESTURE_RIGHT_VOLUME, true),
        gestureHorizontalSeekEnabled = boolean(SettingsStorageKeys.GESTURE_HORIZONTAL_SEEK, true),
        gestureDoubleTapSideEnabled = boolean(SettingsStorageKeys.GESTURE_DOUBLE_TAP_SIDE, true),
        gestureDoubleTapCenterEnabled = boolean(SettingsStorageKeys.GESTURE_DOUBLE_TAP_CENTER, true),
        gestureLongPressSpeedEnabled = boolean(SettingsStorageKeys.GESTURE_LONG_PRESS_SPEED, true),
        gesturePinchZoomEnabled = boolean(SettingsStorageKeys.GESTURE_PINCH_ZOOM, true),
        gestureTopSwipeBackEnabled = boolean(SettingsStorageKeys.GESTURE_TOP_SWIPE_BACK, false),
        gestureSensitivity = int(SettingsStorageKeys.GESTURE_SENSITIVITY, 1).coerceIn(0, 2),
        gestureEdgeGuardEnabled = boolean(SettingsStorageKeys.GESTURE_EDGE_GUARD, true),
        gestureLockEnabled = boolean(SettingsStorageKeys.GESTURE_LOCK, false),
        audioOutputMode = string(SettingsStorageKeys.AUDIO_OUTPUT_MODE, "auto"),
        playerHiddenControls = stringList(SettingsStorageKeys.PLAYER_HIDDEN_CONTROLS).toSet(),
        uaScopeMode = string(SettingsStorageKeys.UA_SCOPE_MODE, "unified"),
        uaForApi = UserAgentSetting.sanitize(string(SettingsStorageKeys.UA_FOR_API, "")),
        uaForSpider = UserAgentSetting.sanitize(string(SettingsStorageKeys.UA_FOR_SPIDER, "")),
        uaForLive = UserAgentSetting.sanitize(string(SettingsStorageKeys.UA_FOR_LIVE, "")),
        uaForEpg = UserAgentSetting.sanitize(string(SettingsStorageKeys.UA_FOR_EPG, "")),
        uaForPlayback = UserAgentSetting.sanitize(string(SettingsStorageKeys.UA_FOR_PLAYBACK, "")),
        uaForWebView = UserAgentSetting.sanitize(string(SettingsStorageKeys.UA_FOR_WEBVIEW, "")),
        uaExportRedacted = boolean(SettingsStorageKeys.UA_EXPORT_REDACTED, true),
        playbackManagerPreferHealthy = boolean(SettingsStorageKeys.PLAYBACK_MANAGER_PREFER_HEALTHY, true),
        playbackManagerManualButton = boolean(SettingsStorageKeys.PLAYBACK_MANAGER_MANUAL_BUTTON, true),
        seekCoolingMs = long(SettingsStorageKeys.SEEK_COOLING_MS, 2_500L),
        manualSourceLockMinutes = int(SettingsStorageKeys.MANUAL_SOURCE_LOCK_MINUTES, 20),
        epgCacheHours = int(SettingsStorageKeys.EPG_CACHE_HOURS, 12).coerceIn(1, 168),
        epgTimezone = string(SettingsStorageKeys.EPG_TIMEZONE, "Asia/Shanghai"),
        epgOutOfRangePolicy = string(SettingsStorageKeys.EPG_OUT_OF_RANGE_POLICY, "clip"),
        platformDefaultQuality = string(SettingsStorageKeys.PLATFORM_DEFAULT_QUALITY, "original"),
        flvProxyMode = string(SettingsStorageKeys.FLV_PROXY_MODE, "auto"),
        platformThirdPartyProxyEnabled = boolean(SettingsStorageKeys.PLATFORM_THIRD_PARTY_PROXY_ENABLED, false),
        platformThirdPartyProxyTemplates = string(SettingsStorageKeys.PLATFORM_THIRD_PARTY_PROXY_TEMPLATES, ""),
        lanResolverEnabled = boolean(SettingsStorageKeys.LAN_RESOLVER_ENABLED, false),
        lanResolverConfirmed = boolean(SettingsStorageKeys.LAN_RESOLVER_CONFIRMED, false),
        aiSearchEnabled = boolean(SettingsStorageKeys.AI_SEARCH_ENABLED, false),
        voiceSearchEnabled = boolean(SettingsStorageKeys.VOICE_SEARCH_ENABLED, false),
        aiTextRedactionEnabled = boolean(SettingsStorageKeys.AI_TEXT_REDACTION, true),
        aiOnlyTitleYearEnabled = boolean(SettingsStorageKeys.AI_ONLY_TITLE_YEAR, true),
        aiMissBehavior = string(SettingsStorageKeys.AI_MISS_BEHAVIOR, "stay"),
        aiResultCacheMinutes = int(SettingsStorageKeys.AI_RESULT_CACHE_MINUTES, 30).coerceIn(0, 24 * 60),
        updateWifiOnly = boolean(SettingsStorageKeys.UPDATE_WIFI_ONLY, true),
        updateExportBeforeInstall = boolean(SettingsStorageKeys.UPDATE_EXPORT_BEFORE_INSTALL, true),
        updateRollbackHours = int(SettingsStorageKeys.UPDATE_ROLLBACK_HOURS, 24).coerceIn(0, 168),
        updateStrongReminderOnly = boolean(SettingsStorageKeys.UPDATE_STRONG_REMINDER_ONLY, true),
        continueWatchingMaxItems = int(SettingsStorageKeys.CONTINUE_WATCHING_MAX_ITEMS, 8).coerceIn(1, 30),
        historyAutoCleanupEnabled = boolean(SettingsStorageKeys.HISTORY_AUTO_CLEANUP, true),
        resumeConflictPolicy = string(SettingsStorageKeys.RESUME_CONFLICT_POLICY, "ask"),
        crashLogLocalOnly = boolean(SettingsStorageKeys.CRASH_LOG_LOCAL_ONLY, true),
    )
}

internal fun decodeLegacyStringList(
    raw: String,
    key: String,
    json: Json = legacySettingsJson,
    onWarning: (String, Throwable) -> Unit = { _, _ -> },
): List<String> {
    if (raw.isBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<String>>(raw) }
        .onFailure { error -> onWarning("配置项 $key 解码失败，已回退为空列表", error) }
        .getOrDefault(emptyList())
}

internal fun decodeLegacyStringMap(
    raw: String,
    key: String,
    json: Json = legacySettingsJson,
    onWarning: (String, Throwable) -> Unit = { _, _ -> },
): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return runCatching { json.decodeFromString<Map<String, String>>(raw) }
        .onFailure { error -> onWarning("配置项 $key 解码失败，已回退为空表", error) }
        .getOrDefault(emptyMap())
}

internal fun decodeLegacyCustomVideoApiLines(
    raw: String,
    json: Json = legacySettingsJson,
    onWarning: (String, Throwable) -> Unit = { _, _ -> },
): List<CustomVideoApiLine> {
    if (raw.isBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<CustomVideoApiLine>>(raw) }
        .onFailure { error -> onWarning("自定义视频源配置解码失败，已回退为空列表", error) }
        .getOrDefault(emptyList())
}

/** 测速延迟：存储用 -1 表示测速失败，读回时还原为 null。 */
internal fun decodeLegacySpeedLatencies(
    raw: String?,
    json: Json = legacySettingsJson,
    onWarning: (String, Throwable) -> Unit = { _, _ -> },
): Map<String, Long?> {
    if (raw.isNullOrBlank()) return emptyMap()
    val decoded = runCatching { json.decodeFromString<Map<String, Long>>(raw) }
        .onFailure { error -> onWarning("测速延迟配置解码失败，已回退为空", error) }
        .getOrNull()
        ?: return emptyMap()
    return decoded.entries.associate { entry -> entry.key to (entry.value.takeIf { it >= 0 }) }
}
