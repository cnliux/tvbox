package com.tvbox.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S1-06 单测：验证 SearchHistoryStore 的 TTL 清理、访客模式跳过与常规行为。
 *
 * 使用 [MockSharedPreferences] 提供纯 JVM 实现，规避 Android Context 依赖。
 */
class SearchHistoryStoreTest {

    private lateinit var prefs: MockSharedPreferences
    private lateinit var store: SearchHistoryStore
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        prefs = MockSharedPreferences()
        store = SearchHistoryStore(prefs, ttlDays = 30)
    }

    // ── TTL：超期条目被过滤 ────────────────────────────────────────────
    @Test
    fun expiredSearchEntriesFilteredOnLoad() = runBlocking {
        val fresh = listOf(SearchEntry("动漫", System.currentTimeMillis()))
        val old = listOf(SearchEntry("老电影", System.currentTimeMillis() - 35L * 86_400_000L))
        writeRaw(fresh + old)

        val result = store.load()
        assertEquals(listOf("动漫"), result)
    }

    @Test
    fun ttlDisabledKeepsAllSearchEntries() = runBlocking {
        val entry = listOf(SearchEntry("远古剧集", System.currentTimeMillis() - 200L * 86_400_000L))
        writeRaw(entry)

        val shortTtl = SearchHistoryStore(prefs, ttlDays = 0)
        val result = shortTtl.load()
        assertEquals(1, result.size)
        assertEquals("远古剧集", result[0])
    }

    // ── 访客模式：add() 被静默忽略 ────────────────────────────────────
    @Test
    fun visitorModeIgnoresAdd() = runBlocking {
        store.isVisitorMode = true
        store.add("禁止搜索的词")

        val result = store.load()
        assertTrue(result.isEmpty())
    }

    // ── 常规功能仍然有效 ──────────────────────────────────────────────
    @Test
    fun normalAddAndLoadWorks() = runBlocking {
        store.add("庆余年")
        store.add("狂飙")
        store.add("三体")
        // 重复添加应去重，最新在前。
        store.add("三体")

        val result = store.load()
        assertEquals(3, result.size)
        assertEquals("三体", result[0])
        assertEquals("狂飙", result[1])
        assertEquals("庆余年", result[2])
    }

    @Test
    fun removeDeletesSpecificEntry() = runBlocking {
        store.add("移除测试")
        store.remove("移除测试")
        assertEquals(emptyList<String>(), store.load())
    }

    @Test
    fun clearEmptiesAllEntries() = runBlocking {
        store.add("清空前记录")
        store.clear()
        assertEquals(emptyList<String>(), store.load())
    }

    @Test
    fun emptyInputIgnored() = runBlocking {
        store.add("   ")
        store.add("")
        assertEquals(emptyList<String>(), store.load())
    }

    // ── 工具方法 ──────────────────────────────────────────────────────
    private fun writeRaw(entries: List<SearchEntry>) {
        // SearchEntry 是内部私有类，通过反射获取实例字段后手动构造 JSON。
        val raw = entries.joinToString(prefix = "[", postfix = "]") { e ->
            """{"keyword":"${e.keyword}","ts":${e.createdAtEpochMs}}"""
        }
        prefs.edit().putString(SearchHistoryStore.KEY_ITEMS, raw).commit()
    }
}
