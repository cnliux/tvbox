package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDomainScoreTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun line(url: String) = LiveChannelLine(name = "线", url = url)

    private fun score(
        domain: String,
        success: Int = 0,
        failure: Int = 0,
        lastSuccessAt: Long = 0,
        lastFailureAt: Long = 0,
    ) = LiveDomainScore(
        domain = domain,
        successCount = success,
        failureCount = failure,
        lastSuccessAtMs = lastSuccessAt,
        lastFailureAtMs = lastFailureAt,
    )

    @Test
    fun `manual override outranks statistics and expires`() {
        val base = mapOf(
            "bad.test" to score("bad.test", failure = 20, lastFailureAt = now),
            "good.test" to score("good.test", success = 5, lastSuccessAt = now),
        )
        // 用户说「这条明明是好的」→ 即使统计是 20 连败也排到第一。
        val overridden = setManualDomainOverride(base, "bad.test", true, now)
        val order = rankLiveLineIndicesByDomain(
            listOf(line("https://good.test/live.m3u8"), line("https://bad.test/live.m3u8")),
            overridden,
            now,
        )
        assertEquals(listOf(1, 0), order)

        // 24h 后裁决失效，回落统计排名。
        val expired = now + LiveDomainScore.MANUAL_OVERRIDE_TTL_MS + 1
        val orderAfterExpiry = rankLiveLineIndicesByDomain(
            listOf(line("https://good.test/live.m3u8"), line("https://bad.test/live.m3u8")),
            overridden,
            expired,
        )
        assertEquals(listOf(0, 1), orderAfterExpiry)

        // 撤销裁决。
        val cleared = setManualDomainOverride(overridden, "bad.test", null, now)
        assertEquals(null, cleared["bad.test"]?.manualOverrideAvailable)
    }

    @Test
    fun `domain is extracted from url`() {
        assertEquals("cdn.example.com", liveLineDomain("https://CDN.example.com/live/1.m3u8?token=x"))
        assertEquals("rtmp.example.com", liveLineDomain("rtmp://rtmp.example.com/live/stream"))
        assertNull(liveLineDomain(""))
        assertNull(liveLineDomain("not-a-url"))
    }

    @Test
    fun `unrecorded domain scores zero`() {
        assertEquals(0.0, LiveDomainScore("x").score(now), 0.0001)
    }

    @Test
    fun `success raises score and failure lowers it`() {
        val good = score("a", success = 3, lastSuccessAt = now)
        val bad = score("b", failure = 3, lastFailureAt = now)
        assertTrue(good.score(now) > 0)
        assertTrue(bad.score(now) < 0)
        assertTrue(good.score(now) > bad.score(now))
    }

    @Test
    fun `failure penalty outweighs equal success count`() {
        // 一成一败的域名净值为负：宁可回退到未知域名，也不优先选"时好时坏"的线路。
        val mixed = score("c", success = 1, failure = 1, lastSuccessAt = now, lastFailureAt = now)
        assertTrue(mixed.score(now) < 0)
    }

    @Test
    fun `score decays with half life`() {
        val fresh = score("d", failure = 4, lastFailureAt = now).score(now)
        val oneHalfLife = score("d", failure = 4, lastFailureAt = now - 12 * hour).score(now)
        val twoHalfLives = score("d", failure = 4, lastFailureAt = now - 24 * hour).score(now)
        assertEquals(fresh / 2, oneHalfLife, 0.001)
        assertEquals(fresh / 4, twoHalfLives, 0.001)
        // 衰减不会反号：失败过的域名只会逐渐回到 0 附近，不会被算成"好"。
        assertTrue(twoHalfLives <= 0.0)
    }

    @Test
    fun `ranking puts healthy domain first and keeps source order otherwise`() {
        val lines = listOf(
            line("http://dead.example.com/1.m3u8"),
            line("http://ok.example.com/1.m3u8"),
            line("http://unknown.example.com/1.m3u8"),
        )
        val scores = mapOf(
            "dead.example.com" to score("dead.example.com", failure = 3, lastFailureAt = now),
            "ok.example.com" to score("ok.example.com", success = 3, lastSuccessAt = now),
        )
        val ranked = rankLiveLineIndicesByDomain(lines, scores, now)
        assertEquals(listOf(1, 2, 0), ranked)
    }

    @Test
    fun `single line channel is untouched`() {
        val lines = listOf(line("http://only.example.com/1.m3u8"))
        assertEquals(listOf(0), rankLiveLineIndicesByDomain(lines, emptyMap(), now))
    }

    @Test
    fun `updates accumulate counts and timestamps`() {
        var scores = updateLiveDomainScores(emptyMap(), "CDN.Example.com", success = true, nowMs = now)
        scores = updateLiveDomainScores(scores, "cdn.example.com", success = false, nowMs = now + 1000)
        val entry = scores.getValue("cdn.example.com")
        assertEquals(1, entry.successCount)
        assertEquals(1, entry.failureCount)
        assertEquals(now, entry.lastSuccessAtMs)
        assertEquals(now + 1000, entry.lastFailureAtMs)
        assertEquals(now + 1000, entry.lastActivityAtMs)
    }

    @Test
    fun `blank domain is ignored`() {
        val scores = updateLiveDomainScores(emptyMap(), "  ", success = true, nowMs = now)
        assertTrue(scores.isEmpty())
    }

    @Test
    fun `entries beyond cap drop the least recently active`() {
        var scores = emptyMap<String, LiveDomainScore>()
        repeat(5) { i ->
            scores = updateLiveDomainScores(scores, "d$i.example.com", success = true, nowMs = now + i, maxEntries = 3)
        }
        assertEquals(3, scores.size)
        assertTrue(scores.containsKey("d4.example.com"))
        assertTrue(!scores.containsKey("d0.example.com"))
    }

    @Test
    fun `hysteresis trims once to low water not on every write`() {
        // 超过高水位才触发一次批量淘汰到低水位（滞回），避免高水位附近每次写入都全表排序。
        var scores = emptyMap<String, LiveDomainScore>()
        repeat(5) { i ->
            scores = updateLiveDomainScores(scores, "h$i.example.com", success = true, nowMs = now + i, maxEntries = 5, lowWater = 3)
        }
        // 第 5 次写入让 size 到 5（=高水位，未超），不应淘汰。
        assertEquals(5, scores.size)

        // 第 6 次写入让 size 到 6（>高水位），一次性淘汰到低水位 3。
        scores = updateLiveDomainScores(scores, "h5.example.com", success = true, nowMs = now + 5, maxEntries = 5, lowWater = 3)
        assertEquals(3, scores.size)
        // 保留最近活动的 3 个（h5、h4、h3），淘汰最早 3 个。
        assertTrue(scores.containsKey("h3.example.com"))
        assertTrue(scores.containsKey("h4.example.com"))
        assertTrue(scores.containsKey("h5.example.com"))
        assertTrue(!scores.containsKey("h0.example.com"))
        assertTrue(!scores.containsKey("h1.example.com"))
    }
}
