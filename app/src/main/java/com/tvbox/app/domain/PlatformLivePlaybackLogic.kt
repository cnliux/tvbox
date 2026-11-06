package com.tvbox.app.domain

/**
 * 平台直播候选线路轮换决策（纯逻辑，便于测试）。
 * 返回下一条候选下标；返回 null 表示候选已轮完一圈或无有效候选，应触发重新解析。
 */
fun nextRotatedPlatformLiveCandidate(stream: ResolvedPlatformLiveStream): Int? {
    if (stream.candidates.isEmpty()) return null
    val currentIdx = stream.safeActiveCandidateIndex
    val nextIdx = (currentIdx + 1) % stream.candidates.size
    if (nextIdx == 0) return null
    return if (stream.candidates.getOrNull(nextIdx) != null) nextIdx else null
}

/** EPG 地址解析：用户配置优先，其次源自带（M3U 头部 url-tvg）。 */
fun resolveEffectiveEpgUrl(userConfiguredUrl: String, bundledUrl: String): String =
    userConfiguredUrl.trim().ifBlank { bundledUrl.trim() }

