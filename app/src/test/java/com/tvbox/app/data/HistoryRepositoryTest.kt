package com.tvbox.app.data

import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.HistoryPolicy
import com.tvbox.app.domain.WatchHistoryItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S1-06 单测：验证 SharedHistoryRepository 的 OFF / TITLE_ONLY 策略、TTL 过滤与访客模式行为。
 *
 * 使用 [MockSharedPreferences] 实现纯 JVM 测试，规避 Android Context 依赖。
 */
class HistoryRepositoryTest {

    private lateinit var prefs: MockSharedPreferences
    private lateinit var repo: SharedHistoryRepository
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        prefs = MockSharedPreferences()
        repo = SharedHistoryRepository(prefs, null)
    }

    // ── OFF 策略：不写盘 ───────────────────────────────────────────────
    @Test
    fun offPolicyRejectsWriteAndReturnsExistingList() = runBlocking {
        val item = baseItem(1, 1_000L)
        val settings = AppSettings(historyPolicy = HistoryPolicy.OFF)
        val wrappedRepo = SharedHistoryRepository(prefs, { settings })

        val result = wrappedRepo.saveProgress(item)
        assertTrue(result.isEmpty())
        assertTrue(prefs.getString("items", null).isNullOrBlank())
    }

    // ── TITLE_ONLY 策略：读取时剥离敏感字段 ───────────────────────────
    @Test
    fun titleOnlyStripsSensitiveFieldsOnRead() = runBlocking {
        val fullItem = baseItem(movieId = 42, positionMs = 55_000L, episodeUrl = "https://secret.ep/ep.mkv")
        writeFullItem(fullItem)

        val settings = AppSettings(historyPolicy = HistoryPolicy.TITLE_ONLY)
        val wrappedRepo = SharedHistoryRepository(prefs, { settings })

        val history = wrappedRepo.getHistory()
        assertEquals(1, history.size)
        val anonymized = history[0]
        assertEquals("", anonymized.apiLineId)
        assertEquals("", anonymized.apiLineName)
        assertEquals("", anonymized.episodeUrl)
        assertEquals(0, anonymized.sourceIndex)
        assertEquals("", anonymized.sourceName)
        assertEquals(0L, anonymized.positionMs)
        // 核心可读信息仍保留。
        assertEquals("影片42", anonymized.movieName)
        assertTrue(anonymized.posterUrl.isNotEmpty())
    }

    // ── FULL 策略：字段完整保留 ───────────────────────────────────────
    @Test
    fun fullPolicyPreservesAllFields() = runBlocking {
        val fullItem = baseItem(movieId = 7, positionMs = 99_000L, episodeUrl = "https://example.com/e7.m3u8")
        writeFullItem(fullItem)

        val settings = AppSettings(historyPolicy = HistoryPolicy.FULL)
        val wrappedRepo = SharedHistoryRepository(prefs, { settings })

        val history = wrappedRepo.getHistory()
        assertEquals(1, history.size)
        assertEquals("ruyi", history[0].apiLineId)
        assertEquals(99_000L, history[0].positionMs)
        assertEquals("https://example.com/e7.m3u8", history[0].episodeUrl)
    }

    // ── TTL：过期条目被自动清除 ──────────────────────────────────────
    @Test
    fun expiredItemsFilteredByTtl() = runBlocking {
        val fresh = baseItem(movieId = 1, positionMs = 1_000L, addedAtEpochMs = System.currentTimeMillis())
        val old = baseItem(movieId = 2, positionMs = 2_000L, addedAtEpochMs = System.currentTimeMillis() - 35L * 86_400_000L)
        writeRaw(listOf(fresh, old))

        val settings = AppSettings(historyPolicy = HistoryPolicy.FULL, searchHistoryTtlDays = 30)
        val wrappedRepo = SharedHistoryRepository(prefs, { settings })

        val history = wrappedRepo.getHistory()
        assertEquals(1, history.size)
        assertEquals(1, history[0].movieId)
    }

    @Test
    fun ttlDisabledKeepsAllItems() = runBlocking {
        val old = baseItem(movieId = 99, positionMs = 100L, addedAtEpochMs = System.currentTimeMillis() - 365L * 86_400_000L)
        writeRaw(listOf(old))

        val settings = AppSettings(historyPolicy = HistoryPolicy.FULL, searchHistoryTtlDays = 0)
        val wrappedRepo = SharedHistoryRepository(prefs, { settings })

        val history = wrappedRepo.getHistory()
        assertEquals(1, history.size)
        assertEquals(99, history[0].movieId)
    }

    // ── 访客模式：跳过写入 ───────────────────────────────────────────
    @Test
    fun guestModeBlocksWrites() = runBlocking {
        val item = baseItem(movieId = 5, positionMs = 3_000L)
        val settings = AppSettings(guestModeActive = true)
        val wrappedRepo = SharedHistoryRepository(prefs, { settings })

        val result = wrappedRepo.saveProgress(item)
        assertTrue(result.isEmpty())
        assertTrue(prefs.getString("items", null).isNullOrBlank())
    }

    // ── OFF 策略：拒绝新写入但保留已有记录 ────────────────────────────

    @Test
    fun offPolicyKeepsExistingItemsAndDoesNotWriteNewOne() = runBlocking {
        val existing = baseItem(movieId = 11, positionMs = 1_000L)
        writeRaw(listOf(existing))
        val rawBefore = prefs.getString("items", null)
        val wrappedRepo = SharedHistoryRepository(prefs) { AppSettings(historyPolicy = HistoryPolicy.OFF) }

        val result = wrappedRepo.saveProgress(baseItem(movieId = 12, positionMs = 2_000L))

        assertEquals(1, result.size)
        assertEquals(11, result[0].movieId)
        assertEquals(rawBefore, prefs.getString("items", null))
    }

    // ── 访客模式：会话内不写盘，退出后无痕 ─────────────────────────────

    @Test
    fun guestSessionWritesNothingAndLeavesNoTraceAfterExit() = runBlocking {
        var guestMode = true
        val wrappedRepo = SharedHistoryRepository(prefs) { AppSettings(guestModeActive = guestMode) }

        val blocked = wrappedRepo.saveProgress(baseItem(movieId = 21))
        assertTrue(blocked.isEmpty())
        assertTrue("访客模式下不得写入观看历史", prefs.getString("items", null).isNullOrBlank())

        guestMode = false
        assertEquals(emptyList<com.tvbox.app.domain.WatchHistoryItem>(), wrappedRepo.getHistory())
        assertTrue(prefs.getString("items", null).isNullOrBlank())

        val saved = wrappedRepo.saveProgress(baseItem(movieId = 22))
        assertEquals(1, saved.size)
        assertEquals(22, saved[0].movieId)
    }

    // ── 工具方法 ──────────────────────────────────────────────────────
    private fun baseItem(
        movieId: Int,
        positionMs: Long = 0L,
        apiLineId: String = "ruyi",
        episodeUrl: String = "",
        addedAtEpochMs: Long = 0L,
    ) = WatchHistoryItem(
        movieId = movieId,
        apiLineId = apiLineId,
        apiLineName = "如意",
        movieName = "影片$movieId",
        posterUrl = "https://example.com/poster$movieId.jpg",
        typeName = "电影",
        remarks = "",
        sourceIndex = 0,
        sourceName = "默认源",
        episodeIndex = 0,
        episodeTitle = "第1集",
        episodeUrl = episodeUrl,
        positionMs = positionMs,
        durationMs = 180_000L,
        updatedAtEpochMs = System.currentTimeMillis(),
        addedAtEpochMs = if (addedAtEpochMs > 0L) addedAtEpochMs else System.currentTimeMillis(),
    )

    private fun writeFullItem(item: WatchHistoryItem) = writeRaw(listOf(item))

    private fun writeRaw(items: List<WatchHistoryItem>) {
        prefs.edit().putString("items", json.encodeToString(items)).commit()
    }
}
