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
        assertEquals(3, candidates.size) // 官方源 + 2 条实测完整返回 APK 的代理（ghproxy.net 因残缺已移除）
        assertEquals("官方源", candidates.first().label)
        assertEquals(releaseUrl, candidates.first().url)
        assertEquals("https://gh-proxy.org/$releaseUrl", candidates[1].url)
        assertEquals("https://gh-proxy.com/$releaseUrl", candidates[2].url)
        assertFalse(candidates.any { it.url.contains("ghproxy.net") })
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
                "https://gh-proxy.org/$releaseUrl",
                mirror,
            ),
        )
        assertEquals("官方源", candidates[0].label)
        assertEquals(mirror, candidates[1].url)
        assertEquals("https://gh-proxy.org/$releaseUrl", candidates[2].url)
        assertEquals(4, candidates.size) // 官方源 + mirror + 2 条代理（gh-proxy.org 与显式镜像去重）
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
    fun manifestCandidatesKeepOfficialRawFirstAndUsableCdnFallbacks() {
        val candidates = buildOtaManifestCandidates()
        assertEquals(7, candidates.size) // raw 官方源 + 3 代理 + 3 jsDelivr 系
        assertEquals("GitHub raw", candidates[0].label)
        assertEquals("https://raw.githubusercontent.com/cnliux/tvbox/main/update.json", candidates[0].url)
        assertEquals("gh-proxy.org", candidates[1].label)
        assertTrue(candidates[1].url.startsWith("https://gh-proxy.org/https://raw.githubusercontent.com/"))
        assertTrue(candidates.any { it.label == "jsDelivr" && it.url.startsWith("https://cdn.jsdelivr.net/gh/") })
        assertFalse(candidates.any { it.label == "ghfast" || it.label == "ghproxy.link" || it.label == "cf.ghproxy.cn" })
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
    fun rankingPrefersProbedAvailableCdnOverUnreachable() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        // 「先检查 CDN 是否可用，再优选」：官方源探活不可达，应排到所有可达/未探活线路之后。
        val availability = mapOf("origin" to false)
        val ranked = rankOtaCdnCandidates(candidates, emptyMap(), nowMs = 1_000L, availability = availability)
        assertEquals("gh-proxy.org", ranked.first().id)
        assertEquals("origin", ranked.last().id)
    }

    @Test
    fun rankingTreatsUntestedCdnAsAvailable() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        // availability 不含该线路（未探活/探活异常）应视为未知、保持原健康度排序，不误杀。
        val ranked = rankOtaCdnCandidates(candidates, emptyMap(), nowMs = 1_000L, availability = emptyMap())
        assertEquals("origin", ranked.first().id)
    }

    @Test
    fun rankingUsesMeasuredSpeedInsteadOfDeclaredOrder() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        // 核心：谁快谁第一，与列表声明顺序无关。官方源声明在第一，但实测最慢应排最后。
        val latencyMs = mapOf(
            "origin" to 2_000L,
            "gh-proxy.org" to 900L,
            "gh-proxy.com" to 150L, // 最快
        )
        val ranked = rankOtaCdnCandidates(
            candidates, emptyMap(), nowMs = 1_000L, latencyMs = latencyMs,
        )
        assertEquals("gh-proxy.com", ranked.first().id)
        assertEquals("origin", ranked.last().id)
        assertEquals(listOf("gh-proxy.com", "gh-proxy.org", "origin"), ranked.map { it.id })
    }

    @Test
    fun rankingSpeedWinsButUnreachableStillSinksToBottom() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        // gh-proxy.org 测得最快，但探活判定不可达，必须仍置底，不能因快而抢第一。
        val latencyMs = mapOf("gh-proxy.org" to 100L, "gh-proxy.com" to 800L, "origin" to 900L)
        val availability = mapOf("gh-proxy.org" to false)
        val ranked = rankOtaCdnCandidates(
            candidates, emptyMap(), nowMs = 1_000L,
            availability = availability, latencyMs = latencyMs,
        )
        assertEquals("gh-proxy.com", ranked.first().id)
        assertEquals("gh-proxy.org", ranked.last().id)
    }

    @Test
    fun rankingIgnoresNonPositiveLatencyAsUnknown() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        // -1（不支持 Range）与 0 视为未知，不加速度分，保持中性排序。
        val latencyMs = mapOf("origin" to -1L, "gh-proxy.org" to 0L)
        val ranked = rankOtaCdnCandidates(
            candidates, emptyMap(), nowMs = 1_000L, latencyMs = latencyMs,
        )
        assertEquals(candidates, ranked)
    }

    @Test
    fun probePlanCapsProbesAndBudgetsEvenly() {
        val candidates = buildOtaManifestCandidates() // 7 条
        val plan = buildCdnProbePlan(candidates, budgetMs = 3_000L, maxProbes = 3)
        assertEquals(3, plan.size)
        plan.forEach { (_, ms) -> assertEquals(1_000L, ms) }
        // maxProbes 放开时不超过候选总数。
        val all = buildCdnProbePlan(candidates, budgetMs = 3_000L, maxProbes = 10)
        assertEquals(candidates.size, all.size)
    }

    @Test
    fun rankingDemotesRepeatedlyFailingCdn() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        val failing = OtaCdnHealth().afterFailure(1_000L).afterFailure(2_000L).afterFailure(3_000L)
        val ranked = rankOtaCdnCandidates(candidates, mapOf("origin" to failing), nowMs = 4_000L)
        assertEquals("gh-proxy.org", ranked.first().id)
        assertEquals("origin", ranked.last().id)
    }

    @Test
    fun rankingRecoversAfterCooldown() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        val staleFailure = OtaCdnHealth(consecutiveFailures = 1, lastFailureAtMs = 1_000L)
        val recentFailure = OtaCdnHealth(consecutiveFailures = 1, lastFailureAtMs = 9_000L)
        val ranked = rankOtaCdnCandidates(
            candidates = candidates,
            health = mapOf("origin" to staleFailure, "gh-proxy.org" to recentFailure),
            nowMs = 1_000L + DEFAULT_OTA_CDN_COOLDOWN_MS + 1L,
        )
        // 冷却后 origin 惩罚衰减为 20，近期失败的 gh-proxy.org 为 100；
        // 从未失败的线路仍按声明顺序在前，但官方源已重新排到 gh-proxy.org 之前。
        assertTrue(ranked.indexOfFirst { it.id == "origin" } < ranked.indexOfFirst { it.id == "gh-proxy.org" })
    }

    @Test
    fun successfulHistoryDoesNotOvertakeNeverFailedOfficialSource() {
        val candidates = buildOtaApkCandidates(releaseUrl)
        val healthy = OtaCdnHealth().afterSuccess(1_000L, 800L).afterSuccess(2_000L, 500L)
        val ranked = rankOtaCdnCandidates(candidates, mapOf("gh-proxy.org" to healthy), nowMs = 3_000L)
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

    @Test
    fun badRangeStitchedApkIsRetryableAndMapsToVerifyFailureText() {
        // 回归：gh-proxy 的 Range 续传可拼出「长度对但 SHA-256 错」的坏包。
        // 系统 DownloadManager 校验失败后走自愈降级（app 内下载器从头重下），
        // 若最终仍失败，失败分类必须允许换线路，且文案归到「安装包校验未通过」。
        // 只有 verifyUpdateApk 硬失败 + 此处正确归因，用户才不会静默装上坏包。
        val badApk = IOException("安装包校验失败，请重新下载")
        assertEquals(OtaCdnFailureKind.RetryOnAnotherCdn, classifyOtaDownloadFailure(badApk))
        assertEquals("安装包校验未通过，请重新下载", otaFailureReason(badApk.message))
        val summary = summarizeOtaCdnFailures(
            listOf(OtaCdnAttempt("gh-proxy.org", badApk.message), OtaCdnAttempt("ghproxy.net", "更新下载失败：HTTP 404")),
        )
        assertTrue(summary.contains("安装包校验未通过"))
        assertFalse(summary.contains("github.com"))
    }
}
