package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OtaCdnTest {

    private val releaseUrl =
        "https://github.com/cnliux/tvbox/releases/download/v1.0.2/juhe-video-v1.0.2.apk"

    @Test
    fun apkCandidatesKeepOfficialSourceFirstAndDeriveProxies() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        assertEquals(4, candidates.size)
        assertEquals("官方源", candidates.first().label)
        assertEquals(releaseUrl, candidates.first().url)
        assertEquals("https://ghfast.top/$releaseUrl", candidates[1].url)
        assertEquals("https://gh-proxy.com/$releaseUrl", candidates[2].url)
        assertEquals("https://ghproxy.net/$releaseUrl", candidates[3].url)
        assertTrue(candidates.all { it.url.startsWith("https://") })
        assertEquals(candidates.size, candidates.distinctBy { it.id }.size)
    }

    @Test
    fun apkCandidatesPutExplicitHttpsMirrorsBeforeDerivedProxiesAndDeduplicate() {
        val mirror = "https://mirror.example.com/tvbox.apk"
        val candidates = buildOtaApkCandidates(
            apkUrl = releaseUrl,
            explicitMirrors = listOf(
                mirror,
                "http://insecure.example.com/tvbox.apk",
                "  ",
                "https://ghfast.top/$releaseUrl",
                mirror,
            ),
        )
        assertEquals("官方源", candidates[0].label)
        assertEquals(mirror, candidates[1].url)
        assertEquals("https://ghfast.top/$releaseUrl", candidates[2].url)
        assertEquals(5, candidates.size) // 显式镜像占用一个 ghfast 位置，其余 2 个代理仍派生
        assertEquals("gh-proxy.com", candidates[3].id)
        assertFalse(candidates.any { it.url.startsWith("http://") })
        assertEquals(1, candidates.count { it.url == mirror })
    }

    @Test
    fun apkCandidatesSkipProxyDerivationForNonGithubUrls() {
        val custom = "https://cdn.example.com/tvbox.apk"
        val candidates = buildOtaApkCandidates(custom)
        assertEquals(1, candidates.size)
        assertEquals(custom, candidates.single().url)
    }

    @Test
    fun apkCandidatesAreEmptyForBlankUrl() {
        assertTrue(buildOtaApkCandidates("  ").isEmpty())
    }

    @Test
    fun manifestCandidatesKeepExistingPrimaryAndBackupFirst() {
        val candidates = buildOtaManifestCandidates()
        assertEquals(7, candidates.size)
        assertEquals("ghfast", candidates[0].label)
        assertTrue(candidates[0].url.startsWith("https://ghfast.top/https://raw.githubusercontent.com/"))
        assertEquals("https://raw.githubusercontent.com/cnliux/tvbox/main/update.json", candidates[1].url)
        assertTrue(candidates.all { it.url.startsWith("https://") })
        assertEquals(candidates.size, candidates.distinctBy { it.url }.size)
    }

    @Test
    fun manifestCandidatesHonourCustomRepository() {
        val candidates = buildOtaManifestCandidates(owner = "acme", repo = "app", branch = "dev", path = "ota.json")
        assertTrue(candidates[1].url.endsWith("/acme/app/dev/ota.json"))
        assertTrue(candidates.any { it.url == "https://cdn.jsdelivr.net/gh/acme/app@dev/ota.json" })
    }

    @Test
    fun rankingKeepsDeclaredOrderWhenNoHealthRecords() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        assertEquals(candidates, rankOtaCdnCandidates(candidates, emptyMap(), nowMs = 10_000L))
    }

    @Test
    fun rankingDemotesRepeatedlyFailingCdn() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        val failing = OtaCdnHealth().afterFailure(1_000L).afterFailure(2_000L).afterFailure(3_000L)
        val ranked = rankOtaCdnCandidates(candidates, mapOf("origin" to failing), nowMs = 4_000L)
        assertEquals("ghfast.top", ranked.first().id)
        assertEquals("origin", ranked.last().id)
    }

    @Test
    fun rankingRecoversAfterCooldown() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        val staleFailure = OtaCdnHealth(consecutiveFailures = 1, lastFailureAtMs = 1_000L)
        val recentFailure = OtaCdnHealth(consecutiveFailures = 1, lastFailureAtMs = 9_000L)
        val ranked = rankOtaCdnCandidates(
            candidates = candidates,
            health = mapOf("origin" to staleFailure, "ghfast.top" to recentFailure),
            nowMs = 1_000L + DEFAULT_OTA_CDN_COOLDOWN_MS + 1L,
        )
        // 冷却后 origin 惩罚衰减为 20，近期失败的 ghfast 为 100；
        // 从未失败的线路仍按声明顺序在前，但官方源已重新排到 ghfast 之前。
        assertTrue(ranked.indexOfFirst { it.id == "origin" } < ranked.indexOfFirst { it.id == "ghfast.top" })
    }

    @Test
    fun successfulHistoryDoesNotOvertakeNeverFailedOfficialSource() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        val healthy = OtaCdnHealth().afterSuccess(1_000L, 800L).afterSuccess(2_000L, 500L)
        val ranked = rankOtaCdnCandidates(candidates, mapOf("ghfast.top" to healthy), nowMs = 3_000L)
        assertEquals("origin", ranked.first().id)
        assertEquals(500L, healthy.bestSuccessMs)
    }

    @Test
    fun healthCodecRoundTripsAndToleratesCorruptedValues() {
        val original = OtaCdnHealth().afterFailure(100L).afterSuccess(200L, 1_500L)
        assertEquals(original, decodeOtaCdnHealth(encodeOtaCdnHealth(original)))
        assertEquals(OtaCdnHealth(), decodeOtaCdnHealth(null))
        assertEquals(OtaCdnHealth(), decodeOtaCdnHealth(""))
        assertEquals(OtaCdnHealth(), decodeOtaCdnHealth("1|2|3"))
        assertEquals(OtaCdnHealth(), decodeOtaCdnHealth("1|2|x|4|5"))
    }

    @Test
    fun failureClassificationSeparatesRetryableFromFatal() {
        assertEquals(
            OtaCdnFailureKind.RetryOnAnotherCdn,
            classifyOtaDownloadFailure(IOException("安装包校验失败，请重新下载")),
        )
        assertEquals(
            OtaCdnFailureKind.RetryOnAnotherCdn,
            classifyOtaDownloadFailure(IOException("更新下载超时，请检查网络后重试")),
        )
        assertEquals(
            OtaCdnFailureKind.RetryOnAnotherCdn,
            classifyOtaDownloadFailure(IOException("更新下载失败：HTTP 404")),
        )
        assertEquals(
            OtaCdnFailureKind.RetryOnAnotherCdn,
            classifyOtaDownloadFailure(IOException("更新下载长时间无进展，请检查网络后重试")),
        )
        assertEquals(
            OtaCdnFailureKind.Fatal,
            classifyOtaDownloadFailure(IOException("设备存储空间不足，请清理后重试")),
        )
        assertEquals(
            OtaCdnFailureKind.Fatal,
            classifyOtaDownloadFailure(IOException("下载已中断，请重试")),
        )
        assertEquals(
            OtaCdnFailureKind.Fatal,
            classifyOtaDownloadFailure(CancellationException("cancelled")),
        )
    }

    @Test
    fun retryDelayIsBoundedAndMonotonic() {
        assertEquals(250L, otaCdnRetryDelayMs(-1))
        assertEquals(250L, otaCdnRetryDelayMs(0))
        assertEquals(500L, otaCdnRetryDelayMs(1))
        assertEquals(1_000L, otaCdnRetryDelayMs(2))
        assertEquals(2_000L, otaCdnRetryDelayMs(3))
        assertEquals(2_000L, otaCdnRetryDelayMs(20))
    }

    @Test
    fun stalledDetectionCoversZeroProgressAndFrozenBytes() {
        // 刚开始下载（0 字节）也受停滞阈值约束：PENDING/RUNNING 假死不再等 15 分钟整体截止。
        assertTrue(isOtaDownloadStalled(0L, 0L, 0L, DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS))
        assertFalse(isOtaDownloadStalled(0L, 0L, 0L, DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS - 1))
        // 字节有推进：即使超过阈值也不算停滞。
        assertFalse(isOtaDownloadStalled(1_024L, 0L, 0L, DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS * 10))
        // 中途冻结：字节数不再变化且超过阈值。
        assertTrue(isOtaDownloadStalled(
            4_096L,
            4_096L,
            1_000L,
            1_000L + DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS,
        ))
        assertFalse(isOtaDownloadStalled(
            4_096L,
            4_096L,
            1_000L,
            1_000L + DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS - 1,
        ))
    }

    @Test
    fun progressGateSuppressesRegressionAfterCdnSwitch() {
        val reported = mutableListOf<Int>()
        val gate = OtaDownloadProgressGate { reported += it }
        gate.onProgress(-1) // 未知进度不上报
        gate.onProgress(0)
        gate.onProgress(50)
        gate.onProgress(0)  // 换 CDN 后新线路从头计数
        gate.onProgress(20)  // 仍低于已上报值
        gate.onProgress(60)
        assertEquals(listOf(0, 50, 60), reported)
    }

    @Test
    fun progressGateEmitsCompletionOnlyOnce() {
        val reported = mutableListOf<Int>()
        val gate = OtaDownloadProgressGate { reported += it }
        gate.onProgress(100)
        gate.onProgress(100)
        gate.onProgress(80)
        gate.onProgress(-1)
        assertEquals(listOf(100), reported)
    }

    @Test
    fun progressGateClampsOutOfRangeValues() {
        val reported = mutableListOf<Int>()
        val gate = OtaDownloadProgressGate { reported += it }
        gate.onProgress(Int.MIN_VALUE)
        gate.onProgress(30)
        gate.onProgress(150)
        assertEquals(listOf(30, 100), reported)
    }

    @Test
    fun summaryReportsAttemptCountWithoutLeakingUrls() {
        val attempts = listOf(
            OtaCdnAttempt("官方源", "更新下载失败：HTTP 403"),
            OtaCdnAttempt("ghfast", "安装包校验失败，请重新下载 $releaseUrl"),
        )
        val text = summarizeOtaCdnFailures(attempts)
        assertTrue(text.contains("2 个下载线路"))
        assertTrue(text.contains("安装包校验未通过"))
        assertFalse(text.contains("github.com"))
        assertEquals("更新下载失败，请检查网络后重试", summarizeOtaCdnFailures(emptyList()))
    }

    @Test
    fun summaryMentionsNoProgressWhenLinesStalled() {
        val attempts = listOf(OtaCdnAttempt("官方源", "更新下载长时间无进展，请检查网络后重试"))
        val text = summarizeOtaCdnFailures(attempts)
        assertTrue(text.contains("下载长时间无进展"))
        assertFalse(text.contains("github.com"))
    }
}
