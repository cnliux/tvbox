package com.tvbox.app.data

import android.content.Context
import android.util.Log
import java.io.IOException
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.CustomVideoApiLine
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.VideoScaleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        val blockedFromFile = blockedCategoriesStore.read()
        val settings = AppSettings(
            homeApiLineId = prefs.getString(KEY_HOME_API_LINE_ID, AppSettings().homeApiLineId)
                ?: AppSettings().homeApiLineId,
            customVideoApiLines = readCustomVideoApiLines(),
            sourceOrder = readStringList(KEY_SOURCE_ORDER),
            blockedSourceIds = readStringList(KEY_BLOCKED_SOURCE_IDS),
            liveSourceUrls = readStringList(KEY_LIVE_SOURCE_URLS),
            blockedPlatformSiteIds = readStringList(KEY_BLOCKED_PLATFORM_SITE_IDS),
            blockedVideoCategories = blockedFromFile ?: readStringList(KEY_BLOCKED_VIDEO_CATEGORIES),
            introSkipSeconds = prefs.getInt(KEY_INTRO_SKIP_SECONDS, 0),
            outroSkipSeconds = prefs.getInt(KEY_OUTRO_SKIP_SECONDS, 0),
            homeContinueWatchingEnabled = prefs.getBoolean(KEY_HOME_CONTINUE_WATCHING, false),
            theme = TvTheme.fromStorageKey(prefs.getString(KEY_THEME, TvTheme.Default.storageKey)),
            fontScale = TvFontScale.fromStorageKey(prefs.getString(KEY_FONT_SCALE, TvFontScale.Normal.storageKey)),
            checkUpdatesOnStartup = prefs.getBoolean(KEY_CHECK_UPDATES_ON_STARTUP, true),
            playbackAgentAutoSwitchEnabled = prefs.getBoolean(KEY_PLAYBACK_AGENT_AUTO_SWITCH, true),
            videoScaleMode = VideoScaleMode.fromStorageKey(prefs.getString(KEY_VIDEO_SCALE_MODE, null)),
            deviceMode = DeviceMode.fromStorageKey(prefs.getString(KEY_DEVICE_MODE, null)),
            liveSourceDefaultGroup = prefs.getString(KEY_LIVE_SOURCE_DEFAULT_GROUP, "") ?: "",
            mobileOnboarded = prefs.getBoolean(KEY_MOBILE_ONBOARDED, false),
            liveSourceDefaultIndex = prefs.getInt(KEY_LIVE_SOURCE_DEFAULT_INDEX, -1),
            episodeSortReverse = prefs.getBoolean(KEY_EPISODE_SORT_REVERSE, false),
            videoGridColumns = prefs.getInt(KEY_VIDEO_GRID_COLUMNS, 0),
            epgUrl = prefs.getString(KEY_EPG_URL, "") ?: "",
            platformLiveGridColumns = prefs.getInt(KEY_PLATFORM_LIVE_GRID_COLUMNS, 0),
            liveSourceCardCount = prefs.getInt(KEY_LIVE_SOURCE_CARD_COUNT, 0),
            platformLiveCardCount = prefs.getInt(KEY_PLATFORM_LIVE_CARD_COUNT, 0),
        )
        // 首次升级时把旧偏好里的屏蔽分类迁移到常驻 JSON 文件。
        if (blockedFromFile == null && settings.blockedVideoCategories.isNotEmpty()) {
            blockedCategoriesStore.write(settings.blockedVideoCategories)
        }
        settings
    }

    override suspend fun saveSettings(settings: AppSettings): AppSettings = withContext(Dispatchers.IO) {
        blockedCategoriesStore.write(settings.blockedVideoCategories)
        val committed = prefs.edit()
            .putString(KEY_HOME_API_LINE_ID, settings.homeApiLineId)
            .putString(KEY_CUSTOM_VIDEO_API_LINES, json.encodeToString(settings.customVideoApiLines))
            .putString(KEY_SOURCE_ORDER, json.encodeToString(settings.sourceOrder))
            .putString(KEY_BLOCKED_SOURCE_IDS, json.encodeToString(settings.blockedSourceIds))
            .putString(KEY_LIVE_SOURCE_URLS, json.encodeToString(settings.liveSourceUrls))
            .putString(KEY_BLOCKED_PLATFORM_SITE_IDS, json.encodeToString(settings.blockedPlatformSiteIds))
            .putString(KEY_BLOCKED_VIDEO_CATEGORIES, json.encodeToString(settings.blockedVideoCategories))
            .putInt(KEY_INTRO_SKIP_SECONDS, settings.introSkipSeconds)
            .putInt(KEY_OUTRO_SKIP_SECONDS, settings.outroSkipSeconds)
            .putBoolean(KEY_HOME_CONTINUE_WATCHING, settings.homeContinueWatchingEnabled)
            .putString(KEY_THEME, settings.theme.storageKey)
            .putString(KEY_FONT_SCALE, settings.fontScale.storageKey)
            .putBoolean(KEY_CHECK_UPDATES_ON_STARTUP, settings.checkUpdatesOnStartup)
            .putBoolean(KEY_PLAYBACK_AGENT_AUTO_SWITCH, settings.playbackAgentAutoSwitchEnabled)
            .putString(KEY_VIDEO_SCALE_MODE, settings.videoScaleMode.storageKey)
            .putString(KEY_DEVICE_MODE, settings.deviceMode.storageKey)
            .putString(KEY_LIVE_SOURCE_DEFAULT_GROUP, settings.liveSourceDefaultGroup)
            .putBoolean(KEY_MOBILE_ONBOARDED, settings.mobileOnboarded)
            .putInt(KEY_LIVE_SOURCE_DEFAULT_INDEX, settings.liveSourceDefaultIndex)
            .putBoolean(KEY_EPISODE_SORT_REVERSE, settings.episodeSortReverse)
            .putInt(KEY_VIDEO_GRID_COLUMNS, settings.videoGridColumns)
            .putString(KEY_EPG_URL, settings.epgUrl)
            .putInt(KEY_PLATFORM_LIVE_GRID_COLUMNS, settings.platformLiveGridColumns)
            .putInt(KEY_LIVE_SOURCE_CARD_COUNT, settings.liveSourceCardCount)
            .putInt(KEY_PLATFORM_LIVE_CARD_COUNT, settings.platformLiveCardCount)
            .commit()
        if (!committed) {
            throw IOException("设置写入失败")
        }
        settings
    }

    private fun readCustomVideoApiLines(): List<CustomVideoApiLine> {
        val raw = prefs.getString(KEY_CUSTOM_VIDEO_API_LINES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<CustomVideoApiLine>>(raw) }
            .onFailure { error -> Log.w(TAG, "自定义视频源配置解码失败，已回退为空列表", error) }
            .getOrDefault(emptyList())
    }

    private fun readStringList(key: String): List<String> {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .onFailure { error -> Log.w(TAG, "配置项 $key 解码失败，已回退为空列表", error) }
            .getOrDefault(emptyList())
    }

    override suspend fun getSpeedLatencies(): Map<String, Long?> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_SOURCE_SPEED_LATENCIES, null).orEmpty()
        if (raw.isBlank()) return@withContext emptyMap()
        val decoded = runCatching { json.decodeFromString<Map<String, Long>>(raw) }
            .onFailure { error -> Log.w(TAG, "测速延迟配置解码失败，已回退为空", error) }
            .getOrNull()
        if (decoded == null) {
            emptyMap()
        } else {
            // -1 表示测速失败，还原为 null。
            decoded.entries.associate { entry -> entry.key to (entry.value.takeIf { it >= 0 }) }
        }
    }

    override suspend fun saveSpeedLatencies(latencies: Map<String, Long?>) = withContext(Dispatchers.IO) {
        val stored = latencies.entries.associate { entry ->
            entry.key to (entry.value ?: -1L)
        }
        prefs.edit()
            .putString(KEY_SOURCE_SPEED_LATENCIES, json.encodeToString(stored))
            .apply()
        Unit
    }

    private companion object {
        const val TAG = "AppSettingsRepository"
        const val KEY_HOME_API_LINE_ID = "home_api_line_id"
        const val KEY_CUSTOM_VIDEO_API_LINES = "custom_video_api_lines"
        const val KEY_SOURCE_ORDER = "source_order"
        const val KEY_BLOCKED_SOURCE_IDS = "blocked_source_ids"
        const val KEY_LIVE_SOURCE_URLS = "live_source_urls"
        const val KEY_BLOCKED_PLATFORM_SITE_IDS = "blocked_platform_site_ids"
        const val KEY_BLOCKED_VIDEO_CATEGORIES = "blocked_video_categories"
        const val KEY_INTRO_SKIP_SECONDS = "intro_skip_seconds"
        const val KEY_OUTRO_SKIP_SECONDS = "outro_skip_seconds"
        const val KEY_HOME_CONTINUE_WATCHING = "home_continue_watching_enabled"
        const val KEY_SOURCE_SPEED_LATENCIES = "source_speed_latencies"
        const val KEY_THEME = "theme"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_CHECK_UPDATES_ON_STARTUP = "check_updates_on_startup"
        const val KEY_PLAYBACK_AGENT_AUTO_SWITCH = "playback_agent_auto_switch"
        const val KEY_VIDEO_SCALE_MODE = "video_scale_mode"
        const val KEY_DEVICE_MODE = "device_mode"
        const val KEY_LIVE_SOURCE_DEFAULT_GROUP = "live_source_default_group"
        const val KEY_MOBILE_ONBOARDED = "mobile_onboarded"
        const val KEY_LIVE_SOURCE_DEFAULT_INDEX = "live_source_default_index"
        const val KEY_EPISODE_SORT_REVERSE = "episode_sort_reverse"
        const val KEY_VIDEO_GRID_COLUMNS = "video_grid_columns"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_PLATFORM_LIVE_GRID_COLUMNS = "platform_live_grid_columns"
        const val KEY_LIVE_SOURCE_CARD_COUNT = "live_source_card_count"
        const val KEY_PLATFORM_LIVE_CARD_COUNT = "platform_live_card_count"
    }
}
