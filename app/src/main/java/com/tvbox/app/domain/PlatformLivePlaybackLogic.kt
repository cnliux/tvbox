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
 * 等 IPTV 播放器通用；国内 CDN 稳定，含央视 / 卫视 / 地方台。优先级仅次于用户在设置里填的
 * 地址，高于订阅自带地址（M3U url-tvg）——野生源的 x-tvg-url 经常失效或被污染，
 * 不能让它压过开箱即用的内置节目单。
 * 用户可在设置中填写自定义地址覆盖内置默认；内置默认也失败时仍会尝试订阅自带地址。
 */
const val DEFAULT_EPG_URL = "https://gitee.com/taksssss/tv/raw/main/epg/51zmt.xml.gz"

/**
 * EPG 地址解析。优先级：
 * 1. 用户在设置里配置的 EPG 地址——[userConfiguredUrl]；
 * 2. 内置默认节目单——[defaultUrl]，开箱即用，优先于订阅自带地址；
 * 3. 订阅自带（M3U 头部 url-tvg / x-tvg-url / tvg-url / url-epg）——[bundledUrl]，
 *    仅在用户未配置且内置默认也为空时才作为首选（拉取失败仍会作为备用尝试）。
 * 4. 三者都为空则返回空串（节目单显示「暂无节目单」但不阻塞播放）。
 *
 * 空/空白串视为未提供：用户配置为空时回落内置默认，再回落订阅自带。
 * 入参与返回值都会做 trim 处理。
 */
fun resolveEffectiveEpgUrl(
    userConfiguredUrl: String,
    bundledUrl: String,
    defaultUrl: String = DEFAULT_EPG_URL,
): String =
    userConfiguredUrl.trim().ifBlank { defaultUrl.trim().ifBlank { bundledUrl.trim() } }

/**
 * EPG 拉取候选地址链：优先级与 [resolveEffectiveEpgUrl] 一致，但保留全部可用地址。
 *
 * 真实场景：M3U 头部的 `x-tvg-url` 可能已失效（如 302 到被污染的域名），
 * 若按旧顺序「源自带 > 内置默认」拉取，用户就会看到「暂无节目单」，即便内置默认节目单可用。
 * 调用方按顺序尝试，首个解析出节目的地址即为实际使用地址；内置默认失败时源自带仍有机会。
 * 去空白、按首次出现顺序去重；三者全空返回空列表。
 */
fun epgFetchCandidates(
    userConfiguredUrl: String,
    bundledUrl: String,
    defaultUrl: String = DEFAULT_EPG_URL,
): List<String> = buildList {
    for (candidate in listOf(userConfiguredUrl, defaultUrl, bundledUrl)) {
        val normalized = candidate.trim()
        if (normalized.isNotBlank() && normalized !in this) add(normalized)
    }
}

/**
 * 当前生效 EPG 地址的来源标签（供电视直播「信息」面板展示地址存在性）。
 * 判定规则与 [resolveEffectiveEpgUrl] 一致：用户配置 > 内置默认 > 源自带 > 未设置。
 * 纯函数，可被 JVM 单测覆盖；[effectiveUrl] 传空表示解析链整体返回空（三层都未提供）。
 * [usedUrl] 为回退后真正拉取成功的地址，默认取 [effectiveUrl]；展示实际来源，
 * 避免「内置默认失效 → 源自带节目单生效」时仍标成「内置默认」。
 */
fun resolveEpgSourceLabel(
    userConfiguredUrl: String,
    bundledUrl: String,
    effectiveUrl: String,
    defaultUrl: String = DEFAULT_EPG_URL,
    usedUrl: String = effectiveUrl,
): String {
    val actual = usedUrl.trim().ifBlank { effectiveUrl.trim() }
    return when {
        actual.isBlank() -> "未设置"
        userConfiguredUrl.trim().isNotBlank() && actual == userConfiguredUrl.trim() -> "用户配置"
        actual == defaultUrl.trim() -> "内置默认"
        bundledUrl.trim().isNotBlank() && actual == bundledUrl.trim() -> "源自带"
        // 兜底：非空但既非用户/内置默认/源自带，按地址存在展示。
        else -> "已配置"
    }
}

