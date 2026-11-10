package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 92/116/118/136/138/139/140/155/157/159/167 轮状态层的合并回归。 */
class BatchStateTest {

    @Test
    fun sourceOrderingRespectsPinWeightAndAvailability() {
        val sources = listOf(
            ManagedSource("a", "A", weight = 50),
            ManagedSource("b", "B", weight = 100),
            ManagedSource("d", "D", weight = 100),
            ManagedSource("c", "C", weight = 100, pinned = true),
        )
        val health = mapOf("b" to SourceHealthSnapshot("b", SourceAvailability.Unavailable))
        assertEquals(listOf("c", "d", "b", "a"), orderManagedSources(sources, health).map { it.id })
        assertEquals(listOf("c", "b", "d", "a"), orderManagedSources(sources).map { it.id })
    }

    @Test
    fun unavailableSourcesAreGatedOutOfFanOutUntilCooldown() {
        val failing = SourceHealthSnapshot(
            "x", SourceAvailability.Unavailable, consecutiveFailures = 3, lastCheckedAtMs = 1_000L,
        )
        assertFalse(shouldJoinFanOut(failing, nowMs = 2_000L, cooldownMs = 10_000L))
        assertTrue(shouldJoinFanOut(failing, nowMs = 12_000L, cooldownMs = 10_000L))
        assertTrue(shouldJoinFanOut(null, nowMs = 2_000L))
        assertTrue(shouldJoinFanOut(failing.copy(consecutiveFailures = 1), nowMs = 2_000L))
    }

    @Test
    fun importAndBackupStatusMessagesAreActionable() {
        val failed = ImportUiState(errorKind = SubscriptionErrorKind.Dns)
        assertTrue(importStatusMessage(failed).contains("域名解析失败"))
        assertTrue(importStatusMessage(failed).contains("保留上次成功配置"))
        assertEquals("预览 5 条，跳过 2 条", importStatusMessage(ImportUiState(previewCount = 5, skippedCount = 2)))
        val blocked = backupExportState(hasSensitiveKeys = true)
        assertFalse(blocked.canExport)
        assertTrue(blocked.message!!.contains("阻止导出"))
        assertTrue(backupExportState(hasSensitiveKeys = false).sanitized)
        assertEquals("可用 120ms", sourceStatusLabel(SourceHealthSnapshot("x", SourceAvailability.Available, latencyMs = 120)))
    }

    @Test
    fun platformMemoryRoundTripsAndBadgeUsesLocalTtl() {
        val last = LastWatchedPlatform("douyu", "lol", "12345", atMs = 5_000L)
        assertEquals(last, decodeLastWatchedPlatform(encodeLastWatchedPlatform(last)))
        assertNull(decodeLastWatchedPlatform("v9|douyu|lol|12345|5000"))
        assertEquals("上次观看：douyu", lastWatchedLabel(last))
        assertEquals(LiveBadge.Live, resolveLiveBadge(1_000L, 1_000L + 600_000L))
        assertEquals(LiveBadge.Unknown, resolveLiveBadge(1_000L, 1_000L + 600_001L))
        assertEquals("正在直播", liveBadgeLabel(LiveBadge.Live))
    }

    @Test
    fun cdnPreferenceSelectsOneLineAndFallsBackSafely() {
        assertEquals(CdnPreference(), decodeCdnPreference(null))
        assertEquals(CdnPreference(), decodeCdnPreference("v9|Manual|origin"))
        val pref = CdnPreference(CdnSelectionMode.Manual, "ghfast.top")
        assertEquals(pref, decodeCdnPreference(encodeCdnPreference(pref)))
        val candidates = buildOtaApkCandidates(
            "https://github.com/cnliux/tvbox/releases/download/v1.3.55/tvbox-v1.3.55.apk",
        )
        assertEquals("ghfast.top", applyCdnPreference(candidates, pref).first().id)
        assertEquals(
            candidates,
            applyCdnPreference(candidates, CdnPreference(CdnSelectionMode.Manual, "missing")),
        )
        assertEquals(
            candidates,
            applyCdnPreference(candidates, CdnPreference()),
        )
    }

    @Test
    fun cdnProbePlanSplitsBudgetWithFloor() {
        val candidates = buildOtaManifestCandidates().take(3)
        val plan = buildCdnProbePlan(candidates, budgetMs = 3_000L, maxProbes = 3)
        assertEquals(3, plan.size)
        assertEquals(listOf(1_000L, 1_000L, 1_000L), plan.map { it.second })
        assertEquals(300L, buildCdnProbePlan(candidates, budgetMs = 100L, maxProbes = 2).first().second)
        assertTrue(buildCdnProbePlan(emptyList()).isEmpty())
    }

    @Test
    fun cdnStatusLabelUsesOnlyLocalCounters() {
        assertEquals("未使用", cdnStatusLabel(OtaCdnHealth(), 10_000L))
        val failed = OtaCdnHealth().afterFailure(1_000L).afterFailure(2_000L)
        assertEquals("连续失败 2 次", cdnStatusLabel(failed, 3_000L))
        val healthy = OtaCdnHealth().afterSuccess(1_000L, 800L)
        assertTrue(cdnStatusLabel(healthy, 61_000L).contains("最快 800ms"))
    }

    @Test
    fun startupTimelineAndFrameMetricsArePure() {
        val timeline = StartupTimeline()
            .record(StartupPhase.ProcessStart, 0L)
            .record(StartupPhase.ApplicationInit, 40L)
            .record(StartupPhase.FirstFrame, 300L)
            .record(StartupPhase.Interactive, 500L)
        assertEquals(40L, timeline.phaseDurations()[StartupPhase.ApplicationInit])
        assertEquals(260L, timeline.phaseDurations()[StartupPhase.FirstFrame])
        assertEquals(500L, startupTotalMs(timeline))
        assertEquals(0.25, jankRatio(FrameTimingSample(200, 50)), 0.0001)
        assertEquals(0.0, jankRatio(FrameTimingSample(0, 0)), 0.0001)
        assertTrue(COMPOSE_STABILITY_CHECKLIST.isNotEmpty())
        assertEquals(4, PROGUARD_TIGHTENING_STEPS.size)
    }

    @Test
    fun uiSlicesDerivePagingAndWeightedBudgets() {
        val slice = deriveHomeFeedSlice(HomeFeedPageState(page = 2, pageCount = 3, itemCount = 20), false, null)
        assertEquals(2, slice.page)
        assertEquals(20, slice.itemCount)
        assertEquals(3, deriveHomeFeedPageRequest(slice, reset = false)!!.nextPage)
        assertNull(deriveHomeFeedPageRequest(slice.copy(page = 3), reset = false))
        assertEquals(1, deriveHomeFeedPageRequest(slice, reset = true)!!.nextPage)
        assertEquals(listOf(4_000L, 2_000L), splitSearchTimeoutBudget(listOf(100, 50), 4_000L))
        assertEquals("已检索 1/3 个来源", deriveSearchProgress(SearchSlice(completedSources = 1, totalSources = 3, isLoading = true)))
    }
}
