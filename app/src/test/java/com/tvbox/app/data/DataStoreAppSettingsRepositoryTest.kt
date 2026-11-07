package com.tvbox.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.CustomVideoApiLine
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.VideoScaleMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAppSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    /**
     * 内存版 DataStore：仓库测试需要多次写入，而 Windows 上 JVM 单测走 Android 实现，
     * `renameTo` 无法覆盖已存在文件（Android/Linux 可原子覆盖），故逻辑用例统一用内存实现；
     * 真实文件后端的首写+读取由 [首次读取完整迁移旧偏好并写入 schema 版本] 覆盖。
     */
    private class InMemoryPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(state.value).also { state.value = it }
            }

        fun current(): Preferences = state.value
    }

    private fun createRealStore(fileName: String): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { File(tempFolder.root, "$fileName.preferences_pb") },
        )
    }

    @Test
    fun `首次读取完整迁移旧偏好并写入 schema 版本`() = runBlocking {
        val source = FakeLegacySource(
            LegacySettingsSnapshot(
                values = mapOf(
                    SettingsStorageKeys.HOME_API_LINE_ID to "line-1",
                    SettingsStorageKeys.CUSTOM_VIDEO_API_LINES to legacySettingsJson.encodeToString(
                        listOf(CustomVideoApiLine(id = "a", name = "A", baseUrl = "https://a")),
                    ),
                    SettingsStorageKeys.SOURCE_ORDER to legacySettingsJson.encodeToString(listOf("x", "y")),
                    SettingsStorageKeys.BLOCKED_SOURCE_IDS to legacySettingsJson.encodeToString(listOf("bad")),
                    SettingsStorageKeys.LIVE_SOURCE_URLS to legacySettingsJson.encodeToString(listOf("https://live")),
                    SettingsStorageKeys.BLOCKED_PLATFORM_SITE_IDS to legacySettingsJson.encodeToString(listOf("douyu")),
                    SettingsStorageKeys.INTRO_SKIP_SECONDS to 15,
                    SettingsStorageKeys.OUTRO_SKIP_SECONDS to 30,
                    SettingsStorageKeys.HOME_CONTINUE_WATCHING to true,
                    SettingsStorageKeys.THEME to TvTheme.Cinema.storageKey,
                    SettingsStorageKeys.FONT_SCALE to TvFontScale.ExtraLarge.storageKey,
                    SettingsStorageKeys.CHECK_UPDATES_ON_STARTUP to false,
                    SettingsStorageKeys.PLAYBACK_AGENT_AUTO_SWITCH to false,
                    SettingsStorageKeys.VIDEO_SCALE_MODE to VideoScaleMode.Zoom.storageKey,
                    SettingsStorageKeys.DEVICE_MODE to DeviceMode.Mobile.storageKey,
                    SettingsStorageKeys.LIVE_SOURCE_DEFAULT_GROUP to "央视",
                    SettingsStorageKeys.MOBILE_ONBOARDED to true,
                    SettingsStorageKeys.LIVE_SOURCE_DEFAULT_INDEX to 2,
                    SettingsStorageKeys.EPISODE_SORT_REVERSE to true,
                    SettingsStorageKeys.VIDEO_GRID_COLUMNS to 5,
                    SettingsStorageKeys.EPG_URL to "https://epg",
                    SettingsStorageKeys.PLATFORM_LIVE_GRID_COLUMNS to 4,
                    SettingsStorageKeys.HISTORY_MAX_ITEMS to 300,
                    SettingsStorageKeys.SOURCE_SPEED_LATENCIES to
                        legacySettingsJson.encodeToString(mapOf("a" to 120L, "b" to -1L)),
                ),
                // 屏蔽分类独立文件值优先于偏好里的旧值。
                blockedCategoriesFromFile = listOf("伦理片"),
            ),
        )
        val store = createRealStore("migration")
        val repository = DataStoreAppSettingsRepository(store, source)
        val settings = repository.getSettings()

        assertEquals("line-1", settings.homeApiLineId)
        assertEquals(1, settings.customVideoApiLines.size)
        assertEquals(listOf("x", "y"), settings.sourceOrder)
        assertEquals(listOf("bad"), settings.blockedSourceIds)
        assertEquals(listOf("https://live"), settings.liveSourceUrls)
        assertEquals(listOf("douyu"), settings.blockedPlatformSiteIds)
        assertEquals(listOf("伦理片"), settings.blockedVideoCategories)
        assertEquals(15, settings.introSkipSeconds)
        assertEquals(30, settings.outroSkipSeconds)
        assertTrue(settings.homeContinueWatchingEnabled)
        assertEquals(TvTheme.Cinema, settings.theme)
        assertEquals(TvFontScale.ExtraLarge, settings.fontScale)
        assertEquals(false, settings.checkUpdatesOnStartup)
        assertEquals(false, settings.playbackAgentAutoSwitchEnabled)
        assertEquals(VideoScaleMode.Zoom, settings.videoScaleMode)
        assertEquals(DeviceMode.Mobile, settings.deviceMode)
        assertEquals("央视", settings.liveSourceDefaultGroup)
        assertTrue(settings.mobileOnboarded)
        assertEquals(2, settings.liveSourceDefaultIndex)
        assertTrue(settings.episodeSortReverse)
        assertEquals(5, settings.videoGridColumns)
        assertEquals("https://epg", settings.epgUrl)
        assertEquals(4, settings.platformLiveGridColumns)
        assertEquals(300, settings.historyMaxItems)

        // 迁移结果已原子落盘：schemaVersion 与屏蔽分类都能在 DataStore 里读到。
        assertEquals(SettingsSchema.CURRENT, store.data.first().schemaVersion())
        assertEquals(
            listOf("伦理片"),
            legacySettingsJson.decodeFromString<List<String>>(
                store.data.first()[stringPreferencesKey(SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES)]!!,
            ),
        )
        assertEquals(1, source.reads)
    }

    @Test
    fun `迁移只执行一次后续读写不再触碰旧偏好`() = runBlocking {
        val source = FakeLegacySource()
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, source)

        repository.getSettings()
        repository.saveSettings(AppSettings(theme = TvTheme.Cinema))
        repository.getSettings()
        repository.getSpeedLatencies()
        assertEquals(1, source.reads)
    }

    @Test
    fun `迁移失败不写 schema 版本且下次读取自动重试`() = runBlocking {
        val source = FakeLegacySource(
            snapshot = LegacySettingsSnapshot(
                values = mapOf(SettingsStorageKeys.HISTORY_MAX_ITEMS to 200),
            ),
            failures = 1,
        )
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, source)

        // 首次迁移读旧数据失败：本次直接返回旧值，且没有把半成品写入新存储。
        assertEquals(200, repository.getSettings().historyMaxItems)
        assertEquals(SettingsSchema.LEGACY, store.current().schemaVersion())
        assertTrue(store.current().asMap().isEmpty())

        // 第二次读取自动重试迁移并成功落盘（第 3 次读旧数据是重试成功的这一次）。
        assertEquals(200, repository.getSettings().historyMaxItems)
        assertEquals(SettingsSchema.CURRENT, store.current().schemaVersion())
        assertEquals(3, source.reads)
    }

    @Test
    fun `迁移始终失败时保存抛出异常且不写坏新存储`() {
        val source = FakeLegacySource(failures = Int.MAX_VALUE)
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, source)

        assertThrows(IOException::class.java) {
            runBlocking { repository.saveSettings(AppSettings(theme = TvTheme.Cinema)) }
        }
        assertEquals(SettingsSchema.LEGACY, store.current().schemaVersion())
        assertTrue(store.current().asMap().isEmpty())
    }

    @Test
    fun `保存设置完整读回且不覆盖测速结果`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, FakeLegacySource())

        repository.saveSpeedLatencies(mapOf("a" to 100L, "b" to null))
        val input = AppSettings(
            homeApiLineId = "h",
            customVideoApiLines = listOf(CustomVideoApiLine(id = "id", name = "n", baseUrl = "https://b")),
            sourceOrder = listOf("s1"),
            blockedSourceIds = listOf("b1"),
            liveSourceUrls = listOf("u1"),
            blockedPlatformSiteIds = listOf("douyu"),
            blockedVideoCategories = listOf("分类"),
            introSkipSeconds = 12,
            outroSkipSeconds = 34,
            homeContinueWatchingEnabled = true,
            theme = TvTheme.Cinema,
            fontScale = TvFontScale.Large,
            checkUpdatesOnStartup = false,
            playbackAgentAutoSwitchEnabled = false,
            videoScaleMode = VideoScaleMode.Zoom,
            deviceMode = DeviceMode.Television,
            liveSourceDefaultGroup = "央视",
            mobileOnboarded = true,
            liveSourceDefaultIndex = 3,
            episodeSortReverse = true,
            videoGridColumns = 6,
            epgUrl = "https://epg",
            platformLiveGridColumns = 4,
            continueWatchingCount = 10,
            epgCardRows = 5,
            liveNumberMatchRows = 10,
            liveNumberSearchLimit = 100,
            relatedChannelCount = 20,
            hotWordCount = 24,
            searchSuggestionCount = 6,
            homeHotContentCount = 40,
            doubanHotPageSize = 30,
            doubanHotFallbackLines = 10,
            historyMaxItems = 200,
            maxParallelSourceRequests = 4,
        )
        repository.saveSettings(input)

        assertEquals(input, repository.getSettings())
        assertEquals(mapOf("a" to 100L, "b" to null), repository.getSpeedLatencies())
    }

    @Test
    fun `存储内损坏字段回退默认值且不抛异常`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, FakeLegacySource())
        repository.getSettings()

        store.updateData { current ->
            current.toMutablePreferences().apply {
                this[stringPreferencesKey(SettingsStorageKeys.SOURCE_ORDER)] = "{bad-json"
                this[stringPreferencesKey(SettingsStorageKeys.THEME)] = "unknown-theme"
                this[stringPreferencesKey(SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES)] = "42"
                this[intPreferencesKey(SettingsStorageKeys.HISTORY_MAX_ITEMS)] = 9999
                this[booleanPreferencesKey(SettingsStorageKeys.MOBILE_ONBOARDED)] = true
            }
        }

        val settings = repository.getSettings()
        assertEquals(emptyList<String>(), settings.sourceOrder)
        assertEquals(emptyList<String>(), settings.blockedVideoCategories)
        assertEquals(TvTheme.Default, settings.theme)
        assertEquals(QuantitySettings.HistoryMaxItems.max, settings.historyMaxItems)
        assertTrue(settings.mobileOnboarded)
    }

    @Test
    fun `迁移与保存并发时最终保留用户保存的设置`() = runBlocking {
        val source = FakeLegacySource(
            snapshot = LegacySettingsSnapshot(
                values = mapOf(SettingsStorageKeys.HISTORY_MAX_ITEMS to 300),
            ),
            delayMs = 50,
        )
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, source)
        val saved = AppSettings(historyMaxItems = 200, theme = TvTheme.Cinema)

        val migrationRead = async(Dispatchers.IO) { repository.getSettings() }
        val saveWrite = async(Dispatchers.IO) { repository.saveSettings(saved) }
        migrationRead.await()
        saveWrite.await()

        assertEquals(saved, repository.getSettings())
        assertEquals(SettingsSchema.CURRENT, store.current().schemaVersion())
    }

    @Test
    fun `全新安装迁移默认值并写入 schema`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, FakeLegacySource())

        assertEquals(AppSettings(), repository.getSettings())
        assertEquals(SettingsSchema.CURRENT, store.current().schemaVersion())
    }

    @Test
    fun `测速延迟随迁移保留且负值还原为 null`() = runBlocking {
        val source = FakeLegacySource(
            LegacySettingsSnapshot(
                values = mapOf(
                    SettingsStorageKeys.SOURCE_SPEED_LATENCIES to
                        legacySettingsJson.encodeToString(mapOf("a" to 120L, "b" to -1L)),
                ),
            ),
        )
        val repository = DataStoreAppSettingsRepository(InMemoryPreferencesDataStore(), source)

        val latencies = repository.getSpeedLatencies()
        assertEquals(120L, latencies["a"])
        assertNull(latencies["b"])
    }

    @Test
    fun `屏蔽分类独立文件缺失时回退偏好值`() = runBlocking {
        val source = FakeLegacySource(
            LegacySettingsSnapshot(
                values = mapOf(
                    SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES to
                        legacySettingsJson.encodeToString(listOf("偏好分类")),
                ),
                blockedCategoriesFromFile = null,
            ),
        )
        val repository = DataStoreAppSettingsRepository(InMemoryPreferencesDataStore(), source)

        assertEquals(listOf("偏好分类"), repository.getSettings().blockedVideoCategories)
    }

    private class FakeLegacySource(
        private val snapshot: LegacySettingsSnapshot = LegacySettingsSnapshot(emptyMap()),
        private val failures: Int = 0,
        private val delayMs: Long = 0,
    ) : LegacySettingsSource {
        var reads = 0
            private set

        override suspend fun read(): LegacySettingsSnapshot {
            reads++
            if (delayMs > 0) delay(delayMs)
            if (reads <= failures) throw IOException("模拟旧设置读取失败")
            return snapshot
        }
    }
}
