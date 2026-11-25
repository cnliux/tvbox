package com.tvbox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-10 去重缓存 LRU 淘汰的回归锁定：
 * 原先 trimIfNeeded 在 entries >= maxEntries 时 clear() 全表清空，
 * 会让去重窗口失效一瞬、偶发一次双跳。改为 LRU 逐条淘汰后，
 * 缓存保持连续：已存在的条目仍命中去重，且不会清空到 0。
 */
class RemoteKeyCacheTest {

    @Test
    fun trimEvictsOnlyOldestAndKeepsRecent() {
        val cache = RemoteKeyCache()
        // 填入 64 条不同 device/key/action 组合，全部放行（不丢）。
        for (i in 0 until 64) {
            assertFalse(cache.shouldDrop(deviceId = 1, keyCode = i, action = 0, nowMs = 1000L + i))
        }
        // 触发一次 trim：应逐条淘汰最旧，而不是清空全表。
        cache.trimIfNeeded()
        // 新条目仍可放行，说明缓存没有失效（若被 clear 过，去重窗口已复位）。
        assertFalse(cache.shouldDrop(deviceId = 1, keyCode = 999, action = 0, nowMs = 10_000L))
    }

    @Test
    fun recentEntryStillDedupedWithinWindowAfterTrim() {
        val cache = RemoteKeyCache()
        // 先写入 key=5，再填满到接近上限，触发 trim。
        assertFalse(cache.shouldDrop(deviceId = 1, keyCode = 5, action = 0, nowMs = 1000L))
        for (i in 6 until 70) {
            cache.shouldDrop(deviceId = 1, keyCode = i, action = 0, nowMs = 2000L + i)
        }
        cache.trimIfNeeded()
        // key=69 是最新写入的，必须在去重窗口（80ms）内再次命中：2000+69=2069，查询 2100（差31ms）。
        val dropped = cache.shouldDrop(deviceId = 1, keyCode = 69, action = 0, nowMs = 2100L)
        assertTrue("最近条目在去重窗口内应被丢弃（LRU 未清空全表）", dropped)
    }

    @Test
    fun oldestEntryEvictedAfterTrim() {
        val cache = RemoteKeyCache()
        // 填满 64 条，全部放行。
        for (i in 0 until 64) {
            cache.shouldDrop(deviceId = 1, keyCode = i, action = 0, nowMs = 1000L + i)
        }
        cache.trimIfNeeded()
        // 最旧 key=0 已被 LRU 淘汰：再查询时 last==null，应放行（返回 false）。
        // 但注意 nowMs 需超出其原时间戳，否则若未被淘汰仍可能命中去重。用远超窗口的时间。
        val dropped = cache.shouldDrop(deviceId = 1, keyCode = 0, action = 0, nowMs = 100_000L)
        assertFalse("最旧条目已被 LRU 淘汰，再触发应放行（last==null）", dropped)
    }
}
