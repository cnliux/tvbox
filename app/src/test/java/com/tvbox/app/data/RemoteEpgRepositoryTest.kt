package com.tvbox.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPG 仓库的边界回归：非法 URL 与空输入必须降级为空节目单，
 * 绝不能抛异常或发起网络请求（EPG 是增强项，不阻塞直播主流程）。
 */
class RemoteEpgRepositoryTest {

    private val repository = RemoteEpgRepository()

    @Test
    fun blankUrlReturnsEmptyMap() = runBlocking {
        assertTrue(repository.fetch("").isEmpty())
        assertTrue(repository.fetch("   ").isEmpty())
    }

    @Test
    fun nonHttpUrlReturnsEmptyMapWithoutNetwork() = runBlocking {
        assertTrue(repository.fetch("ftp://example.com/epg.xml").isEmpty())
        assertTrue(repository.fetch("file:///sdcard/epg.xml").isEmpty())
        assertTrue(repository.fetch("not-a-url").isEmpty())
    }
}
