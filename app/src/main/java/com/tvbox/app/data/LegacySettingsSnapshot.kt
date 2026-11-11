package com.tvbox.app.data

import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.CustomVideoApiLine
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.PlayerSpeedSetting
import com.tvbox.app.domain.QuantitySetting
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
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
    const val CHECK_UPDATES_ON_STARTUP = "check_updates_on_startup"
    const val PLAYBACK_AGENT_AUTO_SWITCH = "playback_agent_auto_switch"
    const val VIDEO_SCALE_MODE = "video_scale_mode"
    const val PLAYER_SPEED = "player_speed"
    const val DECODER_STRATEGY = "decoder_strategy"
    const val CONTENT_VIDEO_SCALE_MODES = "content_video_scale_modes"
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
    const val HOME_POSTER_COUNT = "home_poster_count"
    const val PLATFORM_LIVE_POSTER_COUNT = "platform_live_poster_count"
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
        theme = TvTheme.fromStorageKey(string(SettingsStorageKeys.THEME, TvTheme.Default.storageKey)),
        fontScale = TvFontScale.fromStorageKey(string(SettingsStorageKeys.FONT_SCALE, TvFontScale.Normal.storageKey)),
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
        deviceMode = DeviceMode.fromStorageKey(string(SettingsStorageKeys.DEVICE_MODE, "")),
        liveSourceDefaultGroup = string(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_GROUP, ""),
        mobileOnboarded = boolean(SettingsStorageKeys.MOBILE_ONBOARDED, false),
        liveSourceDefaultIndex = int(SettingsStorageKeys.LIVE_SOURCE_DEFAULT_INDEX, -1),
        episodeSortReverse = boolean(SettingsStorageKeys.EPISODE_SORT_REVERSE, false),
        videoGridColumns = int(SettingsStorageKeys.VIDEO_GRID_COLUMNS, 0),
        epgUrl = string(SettingsStorageKeys.EPG_URL, ""),
        platformLiveGridColumns = int(SettingsStorageKeys.PLATFORM_LIVE_GRID_COLUMNS, 0),
        continueWatchingCount = quantity(SettingsStorageKeys.CONTINUE_WATCHING_COUNT, QuantitySettings.ContinueWatchingCount),
        epgCardRows = quantity(SettingsStorageKeys.EPG_CARD_ROWS, QuantitySettings.EpgCardRows),
        liveNumberMatchRows = quantity(SettingsStorageKeys.LIVE_NUMBER_MATCH_ROWS, QuantitySettings.LiveNumberMatchRows),
        liveNumberSearchLimit = quantity(SettingsStorageKeys.LIVE_NUMBER_SEARCH_LIMIT, QuantitySettings.LiveNumberSearchLimit),
        relatedChannelCount = quantity(SettingsStorageKeys.RELATED_CHANNEL_COUNT, QuantitySettings.RelatedChannelCount),
        hotWordCount = quantity(SettingsStorageKeys.HOT_WORD_COUNT, QuantitySettings.HotWordCount),
        searchSuggestionCount = quantity(SettingsStorageKeys.SEARCH_SUGGESTION_COUNT, QuantitySettings.SearchSuggestionCount),
        homeHotContentCount = quantity(SettingsStorageKeys.HOME_HOT_CONTENT_COUNT, QuantitySettings.HomeHotContentCount),
        doubanHotPageSize = quantity(SettingsStorageKeys.DOUBAN_HOT_PAGE_SIZE, QuantitySettings.DoubanHotPageSize),
        doubanHotFallbackLines = quantity(SettingsStorageKeys.DOUBAN_HOT_FALLBACK_LINES, QuantitySettings.DoubanHotFallbackLines),
        historyMaxItems = quantity(SettingsStorageKeys.HISTORY_MAX_ITEMS, QuantitySettings.HistoryMaxItems),
        maxParallelSourceRequests = quantity(
            SettingsStorageKeys.MAX_PARALLEL_SOURCE_REQUESTS,
            QuantitySettings.MaxParallelSourceRequests,
        ),
        homePosterCount = quantity(
            SettingsStorageKeys.HOME_POSTER_COUNT,
            QuantitySettings.HomePosterCount,
        ),
        platformLivePosterCount = quantity(
            SettingsStorageKeys.PLATFORM_LIVE_POSTER_COUNT,
            QuantitySettings.PlatformLivePosterCount,
        ),
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
