package com.tvbox.app.domain

/**
 * 平台直播候选线路轮换决策（纯逻辑，便于测试）。
 * 返回下一条候选下标；返回 null 表示候选已轮完一圈或无有效候选，应触发重新解析。
 *
 * 单候选时返回当前下标而非 null：让调用方（recoverPlatformLiveChannel）走 sameLineRetries
 * 递增 + schedulePlatformLiveReResolve 的退避重解析路径，而不是直接 forceRefresh=true 强制
 * 重新解析——forceRefresh 会清除缓存，导致同一 CDN 的单候选源在每次 ENDED 后都重新请求
 * 平台签名接口，延长起播时间。sameLineRetries 退避（1.5s/3s）给缓存一个恢复窗口。
 *
 * [triedIndexes] 为已失败过的候选下标。调用方必须传入并随新流/手动切换重置，否则
 * 从非 0 候选开始轮换时会把从未尝试过的候选 0 误判为"已轮完一圈"。
 * 不传时保留旧语义（下标 0 视为起点，回到 0 即返回 null）。
 */
fun nextRotatedPlatformLiveCandidate(
    stream: ResolvedPlatformLiveStream,
    triedIndexes: Set<Int>? = null,
): Int? {
    if (stream.candidates.isEmpty()) return null
    val count = stream.candidates.size
    val currentIdx = stream.safeActiveCandidateIndex
    // 单候选时没有"下一条"可言，返回当前下标让调用方决定如何重试（sameLineRetries 递增 + 退避）。
    if (count == 1) return currentIdx
    if (triedIndexes == null) {
        // 未传 tried 集合时保留旧语义：回到下标 0 即视为轮完一圈。
        val nextIdx = (currentIdx + 1) % count
        return if (nextIdx == 0) null else nextIdx.takeIf { stream.candidates.getOrNull(it) != null }
    }
    for (offset in 1 until count) {
        val candidateIndex = (currentIdx + offset) % count
        if (candidateIndex !in triedIndexes && stream.candidates.getOrNull(candidateIndex) != null) {
            return candidateIndex
        }
    }
    return null
}

/**
 * APK 兜底 EPG：用户未配置且源未声明（M3U 无 url-tvg、TXT/订阅无 epg 字段）时的最后一级节目单地址。
 * 公共全量 XMLTV 镜像（HTTPS，满足 CleartextGuard 内置域名要求）；拉取失败静默，
 * EPG 本就是增强项，不阻塞直播主流程。
 */
const val BUILTIN_FALLBACK_EPG_URL = "https://epg.112114.xyz/pp.xml"

/**
 * EPG 地址解析。优先级（用户诉求）：
 * 1. 订阅自带（M3U 头部 url-tvg / x-tvg-url / tvg-url / url-epg）——[bundledUrl]；
 * 2. 用户在设置里配置的「备用 EPG 地址」——[userConfiguredUrl]，仅当订阅未自带时使用；
 * 3. 内置兜底 [BUILTIN_FALLBACK_EPG_URL]。
 *
 * 空/空白串视为未提供：bundled 为空串时回落备用，备用也空时回落兜底。
 * 入参与返回值都会做 trim 处理。
 */
fun resolveEffectiveEpgUrl(userConfiguredUrl: String, bundledUrl: String): String =
    bundledUrl.trim().ifBlank { userConfiguredUrl.trim().ifBlank { BUILTIN_FALLBACK_EPG_URL } }

