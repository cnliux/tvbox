package com.tvbox.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.LiveRemoteDirectionMapping
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 设置与 DataStore 之间的键值编解码。
 *
 * 列表、自定义源等结构化字段沿用旧版 JSON 字符串格式，键名与旧 SharedPreferences 一致，
 * 因此迁移可以逐键搬运，读取也能复用 [readLegacySettings] 的解析规则。
 */

internal fun MutablePreferences.putString(name: String, value: String) {
    this[stringPreferencesKey(name)] = value
}

internal fun MutablePreferences.putInt(name: String, value: Int) {
    this[intPreferencesKey(name)] = value
}

internal fun MutablePreferences.putLong(name: String, value: Long) {
    this[longPreferencesKey(name)] = value
}

internal fun MutablePreferences.putFloat(name: String, value: Float) {
    this[floatPreferencesKey(name)] = value
}

internal fun MutablePreferences.putBoolean(name: String, value: Boolean) {
    this[booleanPreferencesKey(name)] = value
}

/** 标记该存储已迁移到 [SettingsSchema.CURRENT]。 */
internal fun MutablePreferences.markMigrated() {
    putInt(SettingsStorageKeys.SCHEMA_VERSION, SettingsSchema.CURRENT)
}

/** 当前存储的 schema 版本；旧 SharedPreferences 无版本，视为 [SettingsSchema.LEGACY]。 */
internal fun Preferences.schemaVersion(): Int =
    this[intPreferencesKey(SettingsStorageKeys.SCHEMA_VERSION)] ?: SettingsSchema.LEGACY

internal fun MutablePreferences.putSpeedLatencies(
    latencies: Map<String, Long?>,
    json: Json = legacySettingsJson,
) {
    putString(SettingsStorageKeys.SOURCE_SPEED_LATENCIES, encodeSpeedLatencies(latencies, json))
}

internal fun Preferences.rawSpeedLatencies(): String? =
    this[stringPreferencesKey(SettingsStorageKeys.SOURCE_SPEED_LATENCIES)]

/** 把一份完整设置写入目标偏好容器；[SettingsStorageKeys.SOURCE_SPEED_LATENCIES] 由测速接口单独维护。 */
internal fun MutablePreferences.writeSettings(settings: AppSettings, json: Json = legacySettingsJson) {
    putString(SettingsStorageKeys.HOME_API_LINE_ID, settings.homeApiLineId)
    putString(SettingsStorageKeys.CUSTOM_VIDEO_API_LINES, json.encodeToString(settings.customVideoApiLines))
    putString(SettingsStorageKeys.SOURCE_ORDER, json.encodeToString(settings.sourceOrder))
    putString(SettingsStorageKeys.BLOCKED_SOURCE_IDS, json.encodeToString(settings.blockedSourceIds))
    putString(SettingsStorageKeys.LIVE_SOURCE_URLS, json.encodeToString(settings.liveSourceUrls))
    putString(SettingsStorageKeys.BLOCKED_PLATFORM_SITE_IDS, json.encodeToString(settings.blockedPlatformSiteIds))
    putString(SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES, json.encodeToString(settings.blockedVideoCategories))
    putInt(SettingsStorageKeys.INTRO_SKIP_SECONDS, settings.introSkipSeconds)
    putInt(SettingsStorageKeys.OUTRO_SKIP_SECONDS, settings.outroSkipSeconds)
    putBoolean(SettingsStorageKeys.HOME_CONTINUE_WATCHING, settings.homeContinueWatchingEnabled)
    putString(SettingsStorageKeys.FONT_SCALE, settings.fontScale.storageKey)
    putString(SettingsStorageKeys.THEME_ACCENT, settings.themeAccent.storageKey)
    putBoolean(SettingsStorageKeys.CHECK_UPDATES_ON_STARTUP, settings.checkUpdatesOnStartup)
    putBoolean(SettingsStorageKeys.PLAYBACK_AGENT_AUTO_SWITCH, settings.playbackAgentAutoSwitchEnabled)
    putString(SettingsStorageKeys.VIDEO_SCALE_MODE, settings.videoScaleMode.storageKey)
    putFloat(SettingsStorageKeys.PLAYER_SPEED, settings.playerSpeed)
    putString(SettingsStorageKeys.DECODER_STRATEGY, settings.decoderStrategy.storageKey)
    putString(
        SettingsStorageKeys.CONTENT_VIDEO_SCALE_MODES,
        json.encodeToString(settings.contentVideoScaleModes),
    )
    putString(
        SettingsStorageKeys.CONTENT_SUBTITLE_TRACKS,
        json.encodeToString(settings.contentSubtitleTracks),
    )
    putString(SettingsStorageKeys.DEVICE_MODE, settings.deviceMode.storageKey)
    putString(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_GROUP, settings.liveSourceDefaultGroup)
    putBoolean(SettingsStorageKeys.MOBILE_ONBOARDED, settings.mobileOnboarded)
    putInt(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_INDEX, settings.liveSourceDefaultIndex)
    putBoolean(SettingsStorageKeys.EPISODE_SORT_REVERSE, settings.episodeSortReverse)
    putString(SettingsStorageKeys.EPG_URL, settings.epgUrl)
    putInt(SettingsStorageKeys.EPG_CARD_ROWS, settings.epgCardRows)
    putInt(SettingsStorageKeys.LIVE_NUMBER_MATCH_ROWS, settings.liveNumberMatchRows)
    putInt(SettingsStorageKeys.LIVE_NUMBER_SEARCH_LIMIT, settings.liveNumberSearchLimit)
    putLong(SettingsStorageKeys.LIVE_NUMBER_AUTO_COMMIT_MS, settings.liveNumberAutoCommitMs)
    putInt(SettingsStorageKeys.RELATED_CHANNEL_COUNT, settings.relatedChannelCount)
    putInt(SettingsStorageKeys.HOT_WORD_COUNT, settings.hotWordCount)
    putInt(SettingsStorageKeys.SEARCH_SUGGESTION_COUNT, settings.searchSuggestionCount)
    putInt(SettingsStorageKeys.DOUBAN_HOT_PAGE_SIZE, settings.doubanHotPageSize)
    putInt(SettingsStorageKeys.DOUBAN_HOT_FALLBACK_LINES, settings.doubanHotFallbackLines)
    putInt(SettingsStorageKeys.HISTORY_MAX_ITEMS, settings.historyMaxItems)
    putInt(SettingsStorageKeys.MAX_PARALLEL_SOURCE_REQUESTS, settings.maxParallelSourceRequests)
    putBoolean(SettingsStorageKeys.PLAYER_LOG_ENABLED, settings.playerLogEnabled)
    putString(SettingsStorageKeys.UPDATE_CHANNEL, settings.updateChannel.storageKey)
    putLong(SettingsStorageKeys.LIVE_INFO_BANNER_DURATION_MS, settings.liveInfoBannerDurationMs)
    putLong(SettingsStorageKeys.PER_SOURCE_TIMEOUT_MS, settings.perSourceTimeoutMs)
    putBoolean(SettingsStorageKeys.HISTORY_PRIVACY_MODE, settings.historyPrivacyMode)
    putString(SettingsStorageKeys.DECODER_DEVICE_WHITELIST, settings.decoderDeviceWhitelist)
    putString(SettingsStorageKeys.CUSTOM_USER_AGENT, settings.customUserAgent)
    putBoolean(SettingsStorageKeys.SOURCE_HEALTH_CHECK_ENABLED, settings.sourceHealthCheckEnabled)
    putBoolean(SettingsStorageKeys.SOURCE_CIRCUIT_BREAKER, settings.sourceCircuitBreakerEnabled)
    putInt(SettingsStorageKeys.PLAYER_BRIGHTNESS, settings.playerBrightness)
    putBoolean(SettingsStorageKeys.AUTO_NEXT_EPISODE, settings.autoNextEpisodeEnabled)
    putBoolean(SettingsStorageKeys.PLAYER_GESTURES, settings.playerGesturesEnabled)
    putInt(SettingsStorageKeys.FAST_SEEK_STEP, settings.fastSeekStepSeconds)
    putString(SettingsStorageKeys.HISTORY_LAYOUT, settings.historyLayout.storageKey)
    putBoolean(SettingsStorageKeys.HISTORY_COVER_BADGE, settings.historyCoverBadgeEnabled)
    putInt(SettingsStorageKeys.AUTO_NEXT_COUNTDOWN, settings.autoNextCountdownSeconds)
    putLong(SettingsStorageKeys.FIRST_PACKET_TIMEOUT_MS, settings.firstPacketTimeoutMs)
    putInt(SettingsStorageKeys.RECONNECT_ATTEMPTS, settings.reconnectAttempts)
    putLong(SettingsStorageKeys.BUFFER_TARGET_MS, settings.bufferTargetMs)
    putString(SettingsStorageKeys.BACKGROUND_PLAYBACK_MODE, settings.backgroundPlaybackMode)
    putString(SettingsStorageKeys.LIVE_REMOTE_DIRECTION_MAPPING, settings.liveRemoteDirectionMapping.name)
    putString(SettingsStorageKeys.REMOTE_KEY_BINDINGS, settings.remoteKeyBindings.encode())
    putBoolean(SettingsStorageKeys.GESTURE_LEFT_BRIGHTNESS, settings.gestureLeftBrightnessEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_RIGHT_VOLUME, settings.gestureRightVolumeEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_HORIZONTAL_SEEK, settings.gestureHorizontalSeekEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_DOUBLE_TAP_SIDE, settings.gestureDoubleTapSideEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_DOUBLE_TAP_CENTER, settings.gestureDoubleTapCenterEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_LONG_PRESS_SPEED, settings.gestureLongPressSpeedEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_PINCH_ZOOM, settings.gesturePinchZoomEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_TOP_SWIPE_BACK, settings.gestureTopSwipeBackEnabled)
    putInt(SettingsStorageKeys.GESTURE_SENSITIVITY, settings.gestureSensitivity)
    putBoolean(SettingsStorageKeys.GESTURE_EDGE_GUARD, settings.gestureEdgeGuardEnabled)
    putBoolean(SettingsStorageKeys.GESTURE_LOCK, settings.gestureLockEnabled)
    putString(SettingsStorageKeys.AUDIO_OUTPUT_MODE, settings.audioOutputMode)
    putString(
        SettingsStorageKeys.PLAYER_HIDDEN_CONTROLS,
        json.encodeToString(settings.playerHiddenControls.toList().sorted()),
    )
    // 按场景隐藏：只写非空项，避免老版本读到一堆空数组。
    putString(
        SettingsStorageKeys.PLAYER_HIDDEN_CONTROLS_BY_SURFACE,
        json.encodeToString(settings.playerHiddenControlsBySurface.mapValues { it.value.toList().sorted() }),
    )
    putString(SettingsStorageKeys.UA_SCOPE_MODE, settings.uaScopeMode)
    putString(SettingsStorageKeys.UA_FOR_API, settings.uaForApi)
    putString(SettingsStorageKeys.UA_FOR_SPIDER, settings.uaForSpider)
    putString(SettingsStorageKeys.UA_FOR_LIVE, settings.uaForLive)
    putString(SettingsStorageKeys.UA_FOR_EPG, settings.uaForEpg)
    putString(SettingsStorageKeys.UA_FOR_PLAYBACK, settings.uaForPlayback)
    putString(SettingsStorageKeys.UA_FOR_WEBVIEW, settings.uaForWebView)
    putBoolean(SettingsStorageKeys.UA_EXPORT_REDACTED, settings.uaExportRedacted)
    putBoolean(SettingsStorageKeys.PLAYBACK_MANAGER_PREFER_HEALTHY, settings.playbackManagerPreferHealthy)
    putBoolean(SettingsStorageKeys.PLAYBACK_MANAGER_MANUAL_BUTTON, settings.playbackManagerManualButton)
    putLong(SettingsStorageKeys.SEEK_COOLING_MS, settings.seekCoolingMs)
    putInt(SettingsStorageKeys.MANUAL_SOURCE_LOCK_MINUTES, settings.manualSourceLockMinutes)
    putInt(SettingsStorageKeys.EPG_CACHE_HOURS, settings.epgCacheHours)
    putString(SettingsStorageKeys.EPG_TIMEZONE, settings.epgTimezone)
    putString(SettingsStorageKeys.EPG_OUT_OF_RANGE_POLICY, settings.epgOutOfRangePolicy)
    putString(SettingsStorageKeys.PLATFORM_DEFAULT_QUALITY, settings.platformDefaultQuality)
    putString(SettingsStorageKeys.FLV_PROXY_MODE, settings.flvProxyMode)
    putBoolean(SettingsStorageKeys.PLATFORM_THIRD_PARTY_PROXY_ENABLED, settings.platformThirdPartyProxyEnabled)
    putString(SettingsStorageKeys.PLATFORM_THIRD_PARTY_PROXY_TEMPLATES, settings.platformThirdPartyProxyTemplates)
    putBoolean(SettingsStorageKeys.LAN_RESOLVER_ENABLED, settings.lanResolverEnabled)
    putBoolean(SettingsStorageKeys.REMOTE_MANAGE_LAN_ENABLED, settings.remoteManageLanEnabled)
    putBoolean(SettingsStorageKeys.LAN_RESOLVER_CONFIRMED, settings.lanResolverConfirmed)
    putBoolean(SettingsStorageKeys.AI_SEARCH_ENABLED, settings.aiSearchEnabled)
    putBoolean(SettingsStorageKeys.VOICE_SEARCH_ENABLED, settings.voiceSearchEnabled)
    putBoolean(SettingsStorageKeys.AI_TEXT_REDACTION, settings.aiTextRedactionEnabled)
    putBoolean(SettingsStorageKeys.AI_ONLY_TITLE_YEAR, settings.aiOnlyTitleYearEnabled)
    putString(SettingsStorageKeys.AI_MISS_BEHAVIOR, settings.aiMissBehavior)
    putInt(SettingsStorageKeys.AI_RESULT_CACHE_MINUTES, settings.aiResultCacheMinutes)
    putBoolean(SettingsStorageKeys.UPDATE_WIFI_ONLY, settings.updateWifiOnly)
    putBoolean(SettingsStorageKeys.UPDATE_EXPORT_BEFORE_INSTALL, settings.updateExportBeforeInstall)
    putInt(SettingsStorageKeys.UPDATE_ROLLBACK_HOURS, settings.updateRollbackHours)
    putBoolean(SettingsStorageKeys.UPDATE_STRONG_REMINDER_ONLY, settings.updateStrongReminderOnly)
    putInt(SettingsStorageKeys.CONTINUE_WATCHING_MAX_ITEMS, settings.continueWatchingMaxItems)
    putBoolean(SettingsStorageKeys.HISTORY_AUTO_CLEANUP, settings.historyAutoCleanupEnabled)
    putString(SettingsStorageKeys.RESUME_CONFLICT_POLICY, settings.resumeConflictPolicy)
    putBoolean(SettingsStorageKeys.CRASH_LOG_LOCAL_ONLY, settings.crashLogLocalOnly)
    // S1-06 历史记录策略与 TTL。
    putString(SettingsStorageKeys.HISTORY_POLICY, settings.historyPolicy.storageKey)
    putInt(SettingsStorageKeys.SEARCH_HISTORY_TTL_DAYS, settings.searchHistoryTtlDays)
}

/** 把 DataStore 内容还原成旧快照形状，交给同一套解析函数读取。 */
internal fun Preferences.toSettingsValues(): Map<String, Any?> =
    asMap().entries.associate { entry -> entry.key.name to entry.value }

/** 测速延迟编码：null（测速失败）沿用旧格式写为 -1。 */
internal fun encodeSpeedLatencies(latencies: Map<String, Long?>, json: Json = legacySettingsJson): String =
    json.encodeToString(latencies.mapValues { entry -> entry.value ?: -1L })
