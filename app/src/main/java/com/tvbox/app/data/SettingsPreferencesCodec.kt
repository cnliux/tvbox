package com.tvbox.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tvbox.app.domain.AppSettings
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
    putString(SettingsStorageKeys.THEME, settings.theme.storageKey)
    putString(SettingsStorageKeys.FONT_SCALE, settings.fontScale.storageKey)
    putBoolean(SettingsStorageKeys.CHECK_UPDATES_ON_STARTUP, settings.checkUpdatesOnStartup)
    putBoolean(SettingsStorageKeys.PLAYBACK_AGENT_AUTO_SWITCH, settings.playbackAgentAutoSwitchEnabled)
    putString(SettingsStorageKeys.VIDEO_SCALE_MODE, settings.videoScaleMode.storageKey)
    putFloat(SettingsStorageKeys.PLAYER_SPEED, settings.playerSpeed)
    putString(SettingsStorageKeys.DECODER_STRATEGY, settings.decoderStrategy.storageKey)
    putString(
        SettingsStorageKeys.CONTENT_VIDEO_SCALE_MODES,
        json.encodeToString(settings.contentVideoScaleModes),
    )
    putString(SettingsStorageKeys.DEVICE_MODE, settings.deviceMode.storageKey)
    putString(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_GROUP, settings.liveSourceDefaultGroup)
    putBoolean(SettingsStorageKeys.MOBILE_ONBOARDED, settings.mobileOnboarded)
    putInt(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_INDEX, settings.liveSourceDefaultIndex)
    putBoolean(SettingsStorageKeys.EPISODE_SORT_REVERSE, settings.episodeSortReverse)
    putInt(SettingsStorageKeys.VIDEO_GRID_COLUMNS, settings.videoGridColumns)
    putString(SettingsStorageKeys.EPG_URL, settings.epgUrl)
    putInt(SettingsStorageKeys.PLATFORM_LIVE_GRID_COLUMNS, settings.platformLiveGridColumns)
    putInt(SettingsStorageKeys.CONTINUE_WATCHING_COUNT, settings.continueWatchingCount)
    putInt(SettingsStorageKeys.EPG_CARD_ROWS, settings.epgCardRows)
    putInt(SettingsStorageKeys.LIVE_NUMBER_MATCH_ROWS, settings.liveNumberMatchRows)
    putInt(SettingsStorageKeys.LIVE_NUMBER_SEARCH_LIMIT, settings.liveNumberSearchLimit)
    putInt(SettingsStorageKeys.RELATED_CHANNEL_COUNT, settings.relatedChannelCount)
    putInt(SettingsStorageKeys.HOT_WORD_COUNT, settings.hotWordCount)
    putInt(SettingsStorageKeys.SEARCH_SUGGESTION_COUNT, settings.searchSuggestionCount)
    putInt(SettingsStorageKeys.HOME_HOT_CONTENT_COUNT, settings.homeHotContentCount)
    putInt(SettingsStorageKeys.DOUBAN_HOT_PAGE_SIZE, settings.doubanHotPageSize)
    putInt(SettingsStorageKeys.DOUBAN_HOT_FALLBACK_LINES, settings.doubanHotFallbackLines)
    putInt(SettingsStorageKeys.HISTORY_MAX_ITEMS, settings.historyMaxItems)
    putInt(SettingsStorageKeys.MAX_PARALLEL_SOURCE_REQUESTS, settings.maxParallelSourceRequests)
    putInt(SettingsStorageKeys.HOME_POSTER_COUNT, settings.homePosterCount)
    putInt(SettingsStorageKeys.PLATFORM_LIVE_POSTER_COUNT, settings.platformLivePosterCount)
}

/** 把 DataStore 内容还原成旧快照形状，交给同一套解析函数读取。 */
internal fun Preferences.toSettingsValues(): Map<String, Any?> =
    asMap().entries.associate { entry -> entry.key.name to entry.value }

/** 测速延迟编码：null（测速失败）沿用旧格式写为 -1。 */
internal fun encodeSpeedLatencies(latencies: Map<String, Long?>, json: Json = legacySettingsJson): String =
    json.encodeToString(latencies.mapValues { entry -> entry.value ?: -1L })
