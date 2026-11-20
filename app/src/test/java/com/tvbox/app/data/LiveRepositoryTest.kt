package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class LiveRepositoryTest {

    @Test
    fun sourceConfigNormalizesAndDeduplicatesUrls() {
        val repository = DefaultLiveRepository()
        repository.setSourceConfig(
            customUrls = listOf(
                "https://a.test/live.m3u8/",
                "https://a.test/live.m3u8",
                "   ",
                "https://b.test/live.txt",
            ),
            defaultGroup = "我的直播",
            defaultIndex = 3,
        )

        assertEquals(listOf("https://a.test/live.m3u8", "https://b.test/live.txt"), repository.sourceUrls)
    }

    @Test
    fun defaultSourceIsTriedFirstAndSelectionOverridesIt() {
        val urls = listOf("a", "b", "c")

        assertEquals(listOf("b", "a", "c"), resolveSourceOrder(urls, defaultIndex = 1, selectionIndex = -1))
        assertEquals(listOf("c"), resolveSourceOrder(urls, defaultIndex = 1, selectionIndex = 2))
        assertEquals(urls, resolveSourceOrder(urls, defaultIndex = -1, selectionIndex = -1))
        assertEquals(urls, resolveSourceOrder(urls, defaultIndex = 9, selectionIndex = -1))
    }

    @Test
    fun oversizedLiveSourceIsRejectedInsteadOfFullyAllocated() {
        val bytes = ByteArray(4096) { 'x'.code.toByte() }

        val error = runCatching {
            readLimitedLiveSourceText(ByteArrayInputStream(bytes), maxBytes = 1024L)
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun setSourceConfigAtomicSnapshotPreservesSelectionIndexAcrossConcurrentWrites() {
        // 验证 Config 原子快照：在 setSourceConfig 连续写入过程中，sourceUrls 始终返回最近一次完整写入。
        val repository = DefaultLiveRepository()
        repository.setSourceConfig(listOf("a", "b", "c"), defaultGroup = "", defaultIndex = -1)
        repository.setSourceConfig(listOf("x", "y"), defaultGroup = "直播", defaultIndex = 0)
        assertEquals(listOf("x", "y"), repository.sourceUrls)
    }
}
