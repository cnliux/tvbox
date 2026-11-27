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
 * 内置默认 EPG 节目单地址（开箱即用的兜底）。
 *
 * 选用 51zmt（老张的 EPG）Gitee 镜像：标准 XMLTV `.xml.gz` 压缩格式，DIYP影音 / 百川 / TVBox
 * 等 IPTV 播放器通用；国内 CDN 稳定，含央视 / 卫视 / 地方台。优先级最低——用户在设置里填的
 * 地址、订阅自带地址（M3U url-tvg）都优先生效，仅在两者都为空时启用。
 * 用户可在设置中更改此地址（改小写或清空后由解析链回落）。
 */
const val DEFAULT_EPG_URL = "https://gitee.com/taksssss/tv/raw/main/epg/51zmt.xml.gz"

/**
 * EPG 地址解析。优先级：
 * 1. 用户在设置里配置的 EPG 地址——[userConfiguredUrl]；
 * 2. 订阅自带（M3U 头部 url-tvg / x-tvg-url / tvg-url / url-epg）——[bundledUrl]，仅当用户未配置时使用；
 * 3. 内置默认节目单——[defaultUrl]，用户与订阅都未提供时兜底，保证开箱即有节目单。
 * 4. [defaultUrl] 也为空则返回空串（节目单显示「暂无节目单」但不阻塞播放）。
 *
 * 空/空白串视为未提供：用户配置为空时回落订阅自带，再回落内置默认。
 * 入参与返回值都会做 trim 处理。
 */
fun resolveEffectiveEpgUrl(
    userConfiguredUrl: String,
    bundledUrl: String,
    defaultUrl: String = DEFAULT_EPG_URL,
): String =
    userConfiguredUrl.trim().ifBlank { bundledUrl.trim().ifBlank { defaultUrl.trim() } }

/**
 * 当前生效 EPG 地址的来源标签（供电视直播「信息」面板展示地址存在性）。
 * 判定规则与 [resolveEffectiveEpgUrl] 一致：用户配置 > 源自带 > 内置默认 > 未设置。
 * 纯函数，可被 JVM 单测覆盖；[effectiveUrl] 传空表示解析链整体返回空（三层都未提供）。
 */
fun resolveEpgSourceLabel(
    userConfiguredUrl: String,
    bundledUrl: String,
    effectiveUrl: String,
    defaultUrl: String = DEFAULT_EPG_URL,
): String = when {
    effectiveUrl.isBlank() -> "未设置"
    userConfiguredUrl.trim().isNotBlank() -> "用户配置"
    bundledUrl.trim().isNotBlank() && effectiveUrl == bundledUrl.trim() -> "源自带"
    effectiveUrl == defaultUrl.trim() -> "内置默认"
    // 兜底：非空但既非用户/源自带也未命中内置默认（理论不可达），按地址存在展示。
    else -> "已配置"
}

