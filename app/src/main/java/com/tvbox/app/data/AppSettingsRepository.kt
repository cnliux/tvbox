package com.tvbox.app.data

import android.content.Context
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
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        AppSettings(
            homeApiLineId = prefs.getString(KEY_HOME_API_LINE_ID, AppSettings().homeApiLineId)
                ?: AppSettings().homeApiLineId,
            customVideoApiLines = readCustomVideoApiLines(),
            sourceOrder = readStringList(KEY_SOURCE_ORDER),
            disabledBuiltInLineIds = readStringList(KEY_DISABLED_BUILT_IN_LINE_IDS),
            blockedSourceIds = readStringList(KEY_BLOCKED_SOURCE_IDS),
            liveSourceUrls = readStringList(KEY_LIVE_SOURCE_URLS),
            includeBuiltInLiveSources = prefs.getBoolean(KEY_INCLUDE_BUILT_IN_LIVE_SOURCES, true),
            disabledBuiltInLiveSourceIds = readStringList(KEY_DISABLED_BUILT_IN_LIVE_SOURCE_IDS),
            blockedPlatformSiteIds = readStringList(KEY_BLOCKED_PLATFORM_SITE_IDS),
            homeContinueWatchingEnabled = prefs.getBoolean(KEY_HOME_CONTINUE_WATCHING, false),
            theme = TvTheme.fromStorageKey(prefs.getString(KEY_THEME, TvTheme.Default.storageKey)),
            fontScale = TvFontScale.fromStorageKey(prefs.getString(KEY_FONT_SCALE, TvFontScale.Normal.storageKey)),
            aiProviderId = prefs.getString(KEY_AI_PROVIDER_ID, AppSettings().aiProviderId)
                ?: AppSettings().aiProviderId,
            aiModelName = prefs.getString(KEY_AI_MODEL_NAME, AppSettings().aiModelName)
                ?: AppSettings().aiModelName,
            aiApiKey = prefs.getString(KEY_AI_API_KEY, AppSettings().aiApiKey)
                ?: AppSettings().aiApiKey,
            checkUpdatesOnStartup = prefs.getBoolean(KEY_CHECK_UPDATES_ON_STARTUP, true),
            playbackAgentAutoSwitchEnabled = prefs.getBoolean(KEY_PLAYBACK_AGENT_AUTO_SWITCH, true),
            videoScaleMode = VideoScaleMode.fromStorageKey(prefs.getString(KEY_VIDEO_SCALE_MODE, null)),
            deviceMode = DeviceMode.fromStorageKey(prefs.getString(KEY_DEVICE_MODE, null)),
        )
    }

    override suspend fun saveSettings(settings: AppSettings): AppSettings = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_HOME_API_LINE_ID, settings.homeApiLineId)
            .putString(KEY_CUSTOM_VIDEO_API_LINES, json.encodeToString(settings.customVideoApiLines))
            .putString(KEY_SOURCE_ORDER, json.encodeToString(settings.sourceOrder))
            .putString(KEY_DISABLED_BUILT_IN_LINE_IDS, json.encodeToString(settings.disabledBuiltInLineIds))
            .putString(KEY_BLOCKED_SOURCE_IDS, json.encodeToString(settings.blockedSourceIds))
            .putString(KEY_LIVE_SOURCE_URLS, json.encodeToString(settings.liveSourceUrls))
            .putBoolean(KEY_INCLUDE_BUILT_IN_LIVE_SOURCES, settings.includeBuiltInLiveSources)
            .putString(
                KEY_DISABLED_BUILT_IN_LIVE_SOURCE_IDS,
                json.encodeToString(settings.disabledBuiltInLiveSourceIds),
            )
            .putString(KEY_BLOCKED_PLATFORM_SITE_IDS, json.encodeToString(settings.blockedPlatformSiteIds))
            .putBoolean(KEY_HOME_CONTINUE_WATCHING, settings.homeContinueWatchingEnabled)
            .putString(KEY_THEME, settings.theme.storageKey)
            .putString(KEY_FONT_SCALE, settings.fontScale.storageKey)
            .putString(KEY_AI_PROVIDER_ID, settings.aiProviderId)
            .putString(KEY_AI_MODEL_NAME, settings.aiModelName)
            .putString(KEY_AI_API_KEY, settings.aiApiKey)
            .putBoolean(KEY_CHECK_UPDATES_ON_STARTUP, settings.checkUpdatesOnStartup)
            .putBoolean(KEY_PLAYBACK_AGENT_AUTO_SWITCH, settings.playbackAgentAutoSwitchEnabled)
            .putString(KEY_VIDEO_SCALE_MODE, settings.videoScaleMode.storageKey)
            .putString(KEY_DEVICE_MODE, settings.deviceMode.storageKey)
            .apply()
        settings
    }

    private fun readCustomVideoApiLines(): List<CustomVideoApiLine> {
        val raw = prefs.getString(KEY_CUSTOM_VIDEO_API_LINES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<CustomVideoApiLine>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun readStringList(key: String): List<String> {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
    }

    override suspend fun getSpeedLatencies(): Map<String, Long?> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_SOURCE_SPEED_LATENCIES, null).orEmpty()
        if (raw.isBlank()) return@withContext emptyMap()
        val decoded = runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrNull()
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
        const val KEY_HOME_API_LINE_ID = "home_api_line_id"
        const val KEY_CUSTOM_VIDEO_API_LINES = "custom_video_api_lines"
        const val KEY_SOURCE_ORDER = "source_order"
        const val KEY_DISABLED_BUILT_IN_LINE_IDS = "disabled_built_in_line_ids"
        const val KEY_BLOCKED_SOURCE_IDS = "blocked_source_ids"
        const val KEY_LIVE_SOURCE_URLS = "live_source_urls"
        const val KEY_INCLUDE_BUILT_IN_LIVE_SOURCES = "include_built_in_live_sources"
        const val KEY_DISABLED_BUILT_IN_LIVE_SOURCE_IDS = "disabled_built_in_live_source_ids"
        const val KEY_BLOCKED_PLATFORM_SITE_IDS = "blocked_platform_site_ids"
        const val KEY_HOME_CONTINUE_WATCHING = "home_continue_watching_enabled"
        const val KEY_SOURCE_SPEED_LATENCIES = "source_speed_latencies"
        const val KEY_THEME = "theme"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_AI_PROVIDER_ID = "ai_provider_id"
        const val KEY_AI_MODEL_NAME = "ai_model_name"
        const val KEY_AI_API_KEY = "ai_api_key"
        const val KEY_CHECK_UPDATES_ON_STARTUP = "check_updates_on_startup"
        const val KEY_PLAYBACK_AGENT_AUTO_SWITCH = "playback_agent_auto_switch"
        const val KEY_VIDEO_SCALE_MODE = "video_scale_mode"
        const val KEY_DEVICE_MODE = "device_mode"
    }
}
