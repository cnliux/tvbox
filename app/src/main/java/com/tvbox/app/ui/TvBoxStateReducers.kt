package com.tvbox.app.ui

import com.tvbox.app.domain.Category
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.resolveEffectiveEpgUrl
import kotlinx.coroutines.CancellationException

/**
 * 首页/直播页请求结果的状态归并函数（纯函数，便于单测）。
 *
 * 统一在这里实现两类防回归语义：
 *  1. 请求发起后用户已切换选择时，过期响应直接丢弃，不得覆盖新结果；
 *  2. 协程取消不是失败，调用方据此跳过错误写回。
 */

/** 分类结果归并：源已切换时丢弃过期响应，否则按屏蔽词过滤后写入。 */
internal fun TvBoxUiState.applyCategoryResult(
    requestedApiLineId: String,
    categories: List<Category>,
): TvBoxUiState {
    if (selectedApiLineId != requestedApiLineId) return this
    return copy(categories = filterBlockedCategories(categories, appSettings.blockedVideoCategories))
}

/** EPG 结果归并：拉取期间 EPG 地址被改掉时丢弃过期响应。 */
internal fun TvBoxUiState.applyEpgResult(
    requestedUrl: String,
    programs: Map<String, List<EpgProgram>>,
): TvBoxUiState {
    val currentUrl = resolveEffectiveEpgUrl(appSettings.epgUrl, live.epgBundledUrl)
    if (currentUrl != requestedUrl) return this
    return copy(live = live.copy(epgByChannel = programs, epgLoaded = true))
}

/** 隐藏被屏蔽的影片分类，同时隐藏其子分类。 */
internal fun filterBlockedCategories(
    categories: List<Category>,
    blockedNames: List<String>,
): List<Category> {
    if (blockedNames.isEmpty()) return categories
    val blockedIds = categories.filter { it.name in blockedNames }.map { it.id }.toSet()
    return categories.filter { it.name !in blockedNames && it.parentId !in blockedIds }
}

/** 取消不算失败：返回 null 表示调用方应静默跳过错误写回。 */
internal fun Throwable.uiFailureMessageOrNull(): String? =
    if (this is CancellationException) null else userMessage()

internal fun Throwable.userMessage(): String {
    // 不把原始异常（如 "Unexpected JSON token at offset 0 ... JSON input: ..."）直接展示给用户，
    // 统一转换成可读提示，原始内容仅用于本地排查。
    // 先去掉内部异常类名前缀（如 "DouyuResolverError: WebView 初始化超时"），只保留可读部分。
    val raw = localizedMessage.orEmpty().trim()
        .replace(Regex("^[A-Za-z][A-Za-z0-9_.]*(?:Error|Exception):\\s*"), "")
        .trim()
    val friendly = when {
        raw.isBlank() -> "请求失败，请稍后重试"
        raw.contains("timeout", ignoreCase = true) || raw.contains("超时") -> "网络超时，请稍后重试"
        raw.contains("Unable to resolve host", ignoreCase = true) ||
            raw.contains("Failed to connect", ignoreCase = true) ||
            raw.contains("Connection refused", ignoreCase = true) ||
            raw.contains("Unable to connect", ignoreCase = true) -> "网络连接失败，请检查网络"
        raw.contains("JSON input:", ignoreCase = true) ||
            raw.contains("Unexpected JSON", ignoreCase = true) ||
            raw.contains("Expected start of the object", ignoreCase = true) ->
            "当前视频源不支持该操作，请在顶部切换到其他视频源后重试"
        else -> raw
    }
    return friendly.take(80)
}
