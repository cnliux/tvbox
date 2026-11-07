package com.tvbox.app.data

import android.content.Context
import android.util.Log
import java.io.IOException
import com.tvbox.app.domain.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext

interface AppSettingsRepository {
    suspend fun getSettings(): AppSettings
    suspend fun saveSettings(settings: AppSettings): AppSettings

    /** 测速延迟持久化：value 为 null 表示测速失败，缺失的 key 表示未测速。 */
    suspend fun getSpeedLatencies(): Map<String, Long?> = emptyMap()
    suspend fun saveSpeedLatencies(latencies: Map<String, Long?>) {}
}

class SharedAppSettingsRepository(context: Context) : AppSettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val blockedCategoriesStore = BlockedCategoriesStore(context.applicationContext)
    private val json = legacySettingsJson

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        val blockedFromFile = blockedCategoriesStore.read()
        val settings = readLegacySettings(
            LegacySettingsSnapshot(values = prefs.all, blockedCategoriesFromFile = blockedFromFile),
            json,
        ) { message, error -> Log.w(TAG, message, error) }
        // 首次升级时把旧偏好里的屏蔽分类迁移到常驻 JSON 文件。
        if (blockedFromFile == null && settings.blockedVideoCategories.isNotEmpty()) {
            blockedCategoriesStore.write(settings.blockedVideoCategories)
        }
        settings
    }

    override suspend fun saveSettings(settings: AppSettings): AppSettings = withContext(Dispatchers.IO) {
        blockedCategoriesStore.write(settings.blockedVideoCategories)
        val committed = prefs.edit()
            .putString(SettingsStorageKeys.HOME_API_LINE_ID, settings.homeApiLineId)
            .putString(SettingsStorageKeys.CUSTOM_VIDEO_API_LINES, json.encodeToString(settings.customVideoApiLines))
            .putString(SettingsStorageKeys.SOURCE_ORDER, json.encodeToString(settings.sourceOrder))
            .putString(SettingsStorageKeys.BLOCKED_SOURCE_IDS, json.encodeToString(settings.blockedSourceIds))
            .putString(SettingsStorageKeys.LIVE_SOURCE_URLS, json.encodeToString(settings.liveSourceUrls))
            .putString(
                SettingsStorageKeys.BLOCKED_PLATFORM_SITE_IDS,
                json.encodeToString(settings.blockedPlatformSiteIds),
            )
            .putString(
                SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES,
                json.encodeToString(settings.blockedVideoCategories),
            )
            .putInt(SettingsStorageKeys.INTRO_SKIP_SECONDS, settings.introSkipSeconds)
            .putInt(SettingsStorageKeys.OUTRO_SKIP_SECONDS, settings.outroSkipSeconds)
            .putBoolean(SettingsStorageKeys.HOME_CONTINUE_WATCHING, settings.homeContinueWatchingEnabled)
            .putString(SettingsStorageKeys.THEME, settings.theme.storageKey)
            .putString(SettingsStorageKeys.FONT_SCALE, settings.fontScale.storageKey)
            .putBoolean(SettingsStorageKeys.CHECK_UPDATES_ON_STARTUP, settings.checkUpdatesOnStartup)
            .putBoolean(
                SettingsStorageKeys.PLAYBACK_AGENT_AUTO_SWITCH,
                settings.playbackAgentAutoSwitchEnabled,
            )
            .putString(SettingsStorageKeys.VIDEO_SCALE_MODE, settings.videoScaleMode.storageKey)
            .putString(SettingsStorageKeys.DEVICE_MODE, settings.deviceMode.storageKey)
            .putString(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_GROUP, settings.liveSourceDefaultGroup)
            .putBoolean(SettingsStorageKeys.MOBILE_ONBOARDED, settings.mobileOnboarded)
            .putInt(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_INDEX, settings.liveSourceDefaultIndex)
            .putBoolean(SettingsStorageKeys.EPISODE_SORT_REVERSE, settings.episodeSortReverse)
            .putInt(SettingsStorageKeys.VIDEO_GRID_COLUMNS, settings.videoGridColumns)
            .putString(SettingsStorageKeys.EPG_URL, settings.epgUrl)
            .putInt(SettingsStorageKeys.PLATFORM_LIVE_GRID_COLUMNS, settings.platformLiveGridColumns)
            .putInt(SettingsStorageKeys.CONTINUE_WATCHING_COUNT, settings.continueWatchingCount)
            .putInt(SettingsStorageKeys.EPG_CARD_ROWS, settings.epgCardRows)
            .putInt(SettingsStorageKeys.LIVE_NUMBER_MATCH_ROWS, settings.liveNumberMatchRows)
            .putInt(SettingsStorageKeys.LIVE_NUMBER_SEARCH_LIMIT, settings.liveNumberSearchLimit)
            .putInt(SettingsStorageKeys.RELATED_CHANNEL_COUNT, settings.relatedChannelCount)
            .putInt(SettingsStorageKeys.HOT_WORD_COUNT, settings.hotWordCount)
            .putInt(SettingsStorageKeys.SEARCH_SUGGESTION_COUNT, settings.searchSuggestionCount)
            .putInt(SettingsStorageKeys.HOME_HOT_CONTENT_COUNT, settings.homeHotContentCount)
            .putInt(SettingsStorageKeys.DOUBAN_HOT_PAGE_SIZE, settings.doubanHotPageSize)
            .putInt(SettingsStorageKeys.DOUBAN_HOT_FALLBACK_LINES, settings.doubanHotFallbackLines)
            .putInt(SettingsStorageKeys.HISTORY_MAX_ITEMS, settings.historyMaxItems)
            .putInt(SettingsStorageKeys.MAX_PARALLEL_SOURCE_REQUESTS, settings.maxParallelSourceRequests)
            .commit()
        if (!committed) {
            throw IOException("设置写入失败")
        }
        settings
    }

    override suspend fun getSpeedLatencies(): Map<String, Long?> = withContext(Dispatchers.IO) {
        decodeLegacySpeedLatencies(
            prefs.getString(SettingsStorageKeys.SOURCE_SPEED_LATENCIES, null),
            json,
        ) { message, error -> Log.w(TAG, message, error) }
    }

    override suspend fun saveSpeedLatencies(latencies: Map<String, Long?>) = withContext(Dispatchers.IO) {
        val stored = latencies.entries.associate { entry ->
            entry.key to (entry.value ?: -1L)
        }
        prefs.edit()
            .putString(SettingsStorageKeys.SOURCE_SPEED_LATENCIES, json.encodeToString(stored))
            .apply()
        Unit
    }

    private companion object {
        const val TAG = "AppSettingsRepository"
    }
}
