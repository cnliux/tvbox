package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * OTA 多 CDN 下载策略（纯 Kotlin，不依赖 Android，可直接 JVM 单测）。
 *
 * 设计约束：
 * - `update.json` 既有 8 个字段（versionCode / versionName / apkUrl / apkSha256 /
 *   apkSize / force / changelog）保持不变；`apkMirrors` 只是**可选**扩展字段，
 *   老清单没有该字段时由客户端从 `apkUrl` 派生 CDN 候选。
 * - 默认顺序必须让「官方源 / 现有主源」保持第一，避免引入无法预期的行为变化；
 *   只有在本机记录到连续失败后，健康度排序才会把该线路降权。
 * - 所有派生 / 显式镜像都强制 HTTPS；原始 `apkUrl` 保持调用方给出的值不变。
 */

/**
 * 默认用于 GitHub Release APK 的代理 CDN（按优先级排列）。
 * 2026-11-16 全量实测后重排：
 * - `gh-proxy.org` 为 gh-proxy.com 官方新域名（2025-11 起启用），2MB/2.5s，最快，置顶；
 * - `gh-proxy.com` 旧域名仍可用，2MB/8.8s，保留为第二候选；
 * - `ghproxy.net` 可用但偏慢（2MB/15s），留作兜底；
 * - 已移除 `ghfast.top`（30s 仅 720KB，20MB APK 实际下不完）、`ghproxy.link` 与
 *   `cf.ghproxy.cn`（HTTP 200 但返回 HTML 页面，非 APK）。
 */
val DEFAULT_OTA_APK_CDN_HOSTS: List<String> = listOf(
    "gh-proxy.org",
    "gh-proxy.com",
    "ghproxy.net",
)

/** 连续失败的降权冷却时间：超过后惩罚衰减到 20%，给线路重新尝试的机会。 */
const val DEFAULT_OTA_CDN_COOLDOWN_MS = 6L * 60L * 60L * 1000L

/** CDN 切换的最大退避间隔。 */
const val MAX_OTA_CDN_RETRY_DELAY_MS = 2_000L

/** 下载进度停滞判定阈值：已收字节持续无变化超过该时长视为线路假死，快速失败并切换下一条线路。 */
const val DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS = 90_000L

data class OtaCdnCandidate(
    /** 稳定标识，用于本机健康度持久化；不要用完整 URL 当 key。 */
    val id: String,
    /** 面向用户的简短线路名，也会用于日志（不含 URL，天然脱敏）。 */
    val label: String,
    val url: String,
)

/**
 * 单条 CDN 探活结果。[elapsedMs] 为下载固定字节的端到端耗时（越小越快）；
 * -1 表示线路可达但未能测速（如不支持 Range），排序时保持中性、不误杀。
 */
data class OtaCdnProbe(
    val reachable: Boolean,
    val elapsedMs: Long = -1L,
)

data class OtaCdnHealth(
    val consecutiveFailures: Int = 0,
    val successCount: Int = 0,
    val lastSuccessAtMs: Long = 0L,
    val lastFailureAtMs: Long = 0L,
    /** 历史最快一次成功耗时（毫秒），0 表示未知。 */
    val bestSuccessMs: Long = 0L,
)

fun OtaCdnHealth.afterSuccess(nowMs: Long, elapsedMs: Long): OtaCdnHealth {
    val safeElapsed = elapsedMs.coerceAtLeast(0L)
    return copy(
        consecutiveFailures = 0,
        successCount = successCount + 1,
        lastSuccessAtMs = nowMs,
        bestSuccessMs = if (bestSuccessMs <= 0L) safeElapsed else minOf(bestSuccessMs, safeElapsed),
    )
}

fun OtaCdnHealth.afterFailure(nowMs: Long): OtaCdnHealth = copy(
    consecutiveFailures = consecutiveFailures + 1,
    lastFailureAtMs = nowMs,
)

/** SharedPreferences 里用一条竖线分隔的字符串承载健康度，避免额外序列化注解。 */
fun encodeOtaCdnHealth(health: OtaCdnHealth): String = listOf(
    health.consecutiveFailures,
    health.successCount,
    health.lastSuccessAtMs,
    health.lastFailureAtMs,
    health.bestSuccessMs,
).joinToString("|")

/** 容错解析：字段缺失 / 非法数字 / 旧格式都退化为默认健康度，不影响下载主流程。 */
fun decodeOtaCdnHealth(raw: String?): OtaCdnHealth {
    if (raw.isNullOrBlank()) return OtaCdnHealth()
    val parts = raw.split('|')
    if (parts.size < 5) return OtaCdnHealth()
    val values = parts.take(5).map { it.trim().toLongOrNull() ?: return OtaCdnHealth() }
    return OtaCdnHealth(
        consecutiveFailures = values[0].coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        successCount = values[1].coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        lastSuccessAtMs = values[2].coerceAtLeast(0L),
        lastFailureAtMs = values[3].coerceAtLeast(0L),
        bestSuccessMs = values[4].coerceAtLeast(0L),
    )
}

/**
 * 生成 APK 下载候选：官方源 → 清单显式镜像（可选）→ 由 GitHub Release 地址派生的代理 CDN。
 *
 * - 只接受 HTTPS 的镜像 / 代理，HTTP 与空白地址会被丢弃；
 * - 按 URL 去重，避免 `apkMirrors` 与内置派生结果重复下载；
 * - 非 GitHub Release 地址（用户自建 CDN）不套代理，只使用原地址与显式镜像。
 */
fun buildOtaApkCandidates(
    apkUrl: String,
    explicitMirrors: List<String> = emptyList(),
    proxyHosts: List<String> = DEFAULT_OTA_APK_CDN_HOSTS,
): List<OtaCdnCandidate> {
    val origin = apkUrl.trim()
    if (origin.isEmpty()) return emptyList()
    val result = mutableListOf<OtaCdnCandidate>()

    fun add(id: String, label: String, url: String, isOrigin: Boolean = false) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        if (!isOrigin && !trimmed.startsWith("https://", ignoreCase = true)) return
        if (result.any { it.url == trimmed }) return
        result += OtaCdnCandidate(id = id, label = label, url = trimmed)
    }

    add("origin", "官方源", origin, isOrigin = true)
    explicitMirrors.forEachIndexed { index, mirror ->
        add("mirror-$index", "自定义镜像 ${index + 1}", mirror)
    }
    val isGithubRelease = origin.startsWith("https://github.com/", ignoreCase = true) &&
        origin.contains("/releases/download/", ignoreCase = true)
    if (isGithubRelease) {
        proxyHosts.forEach { host ->
            val cleanHost = host.trim().removePrefix("https://").trimEnd('/')
            if (cleanHost.isNotBlank()) {
                add(cleanHost, cleanHost, "https://$cleanHost/$origin")
            }
        }
    }
    return result
}

/**
 * 生成更新清单候选。GitHub raw 官方源保持第一，随后是按 2026-11-16 实测速率排序的 CDN；
 * jsDelivr 系对分支有缓存，只作兜底，versionCode 比对保证旧缓存不会触发降级安装。
 *
 * 已移除：`ghfast.top`（实测极慢，2MB 下载 30s 仅 720KB）、`ghproxy.link` 与
 * `cf.ghproxy.cn`（返回 HTML 页面而非清单）。新增 gh-proxy.com 官方新域名 `gh-proxy.org`。
 */
fun buildOtaManifestCandidates(
    owner: String = "cnliux",
    repo: String = "tvbox",
    branch: String = "main",
    path: String = "update.json",
): List<OtaCdnCandidate> {
    val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
    val jsdelivrPath = "/gh/$owner/$repo@$branch/$path"
    return listOf(
        OtaCdnCandidate("manifest-raw", "GitHub raw", raw),
        OtaCdnCandidate("manifest-gh-proxy-org", "gh-proxy.org", "https://gh-proxy.org/$raw"),
        OtaCdnCandidate("manifest-gh-proxy", "gh-proxy", "https://gh-proxy.com/$raw"),
        OtaCdnCandidate("manifest-ghproxy-net", "ghproxy.net", "https://ghproxy.net/$raw"),
        OtaCdnCandidate("manifest-jsdelivr", "jsDelivr", "https://cdn.jsdelivr.net$jsdelivrPath"),
        OtaCdnCandidate("manifest-fastly", "Fastly jsDelivr", "https://fastly.jsdelivr.net$jsdelivrPath"),
        OtaCdnCandidate("manifest-gcore", "Gcore jsDelivr", "https://gcore.jsdelivr.net$jsdelivrPath"),
    )
}

/**
 * 按「实测速度优先」排序候选：谁快谁排前面，不再由声明顺序决定谁是第一。
 *
 * 排序依据（权重从高到低）：
 * 1. 不可达线路重罚置底（[availability] 明确为 false 时）；
 * 2. **实测速度**：[latencyMs] 中有数据的线路，按相对本轮最快线路的耗时差打分——
 *    越快分越低排越前，以此实现「根据哪个速度快用哪一个」；
 * 3. 历史连续失败作为次要惩罚（冷却期后衰减），避免偶发失败永久埋没一条线路；
 * 4. 速度与失败都无记录的线路保持声明顺序，绝不被误杀。
 *
 * [latencyMs] 键为候选 id，值为本次探活下载固定字节的耗时（毫秒，越小越快）。
 * 键不存在（未参与探活 / 探活异常）视为未知，该线路不加速度分，保持中性。
 */
fun rankOtaCdnCandidates(
    candidates: List<OtaCdnCandidate>,
    health: Map<String, OtaCdnHealth>,
    nowMs: Long,
    availability: Map<String, Boolean> = emptyMap(),
    latencyMs: Map<String, Long> = emptyMap(),
    cooldownMs: Long = DEFAULT_OTA_CDN_COOLDOWN_MS,
): List<OtaCdnCandidate> {
    // 以本轮最快的有效耗时为基准；耗时必须为正才参与，避免 0/负值造成除零或倒挂。
    val validLatencies = latencyMs.values.filter { it > 0L }
    val fastestMs = validLatencies.minOrNull()
    return candidates
        .mapIndexed { index, candidate ->
            val record = health[candidate.id] ?: OtaCdnHealth()
            val failureAgeMs = if (record.lastFailureAtMs > 0L) nowMs - record.lastFailureAtMs else 0L
            val decay = if (record.consecutiveFailures > 0 && failureAgeMs >= cooldownMs) 0.2 else 1.0
            val failurePenalty = minOf(record.consecutiveFailures, 6) * 80.0 * decay
            // 不可达：重罚置底，高于所有速度分与历史失败上限。
            val availPenalty = if (availability[candidate.id] == false) 10_000.0 else 0.0
            // 速度分：相对最快线路的倍数差。最快=0；慢 N 倍约 (N-1)*SPEED_WEIGHT，封顶 8 倍。
            // 优先级：已测速可达(0~960) < 未测速未知(1000) < 不可达(10000)。
            // 未测速给中性偏后的固定分，确保「已测得可用的线路」永远排在「还没测出速度」之前；
            // 当全部线路都未测速时该分相同，退回声明顺序，不误杀。
            val latency = latencyMs[candidate.id]?.takeIf { it > 0L }
            val speedPenalty = if (latency != null && fastestMs != null) {
                ((latency.toDouble() / fastestMs) - 1.0).coerceIn(0.0, 8.0) * SPEED_PENALTY_WEIGHT
            } else SPEED_UNKNOWN_PENALTY
            RankedOtaCandidate(
                score = availPenalty + speedPenalty + failurePenalty,
                index = index,
                candidate = candidate,
            )
        }
        .sortedWith(compareBy<RankedOtaCandidate> { it.score }.thenBy { it.index })
        .map { it.candidate }
}

/** 速度分权重：慢一倍约 +120 分，8 倍封顶共 960 分。 */
private const val SPEED_PENALTY_WEIGHT = 120.0

/** 未测速线路的分数：大于已测速上限 960、小于不可达罚分 10000，处于中性偏后位置。 */
private const val SPEED_UNKNOWN_PENALTY = 1_000.0

private data class RankedOtaCandidate(
    val score: Double,
    val index: Int,
    val candidate: OtaCdnCandidate,
)

enum class OtaCdnFailureKind {
    /** 换一条 CDN 重试有意义（DNS / 超时 / HTTP / 校验失败等）。 */
    RetryOnAnotherCdn,

    /** 换 CDN 也解决不了（空间不足、目录不可写、用户取消、服务不可用）。 */
    Fatal,
}

private val FATAL_OTA_MESSAGE_TOKENS = listOf(
    "空间不足",
    "INSUFFICIENT_SPACE",
    // #321：签名冲突（与已安装版本签名不一致）换 CDN 重下也不会成功，按致命错误处理。
    "签名冲突",
    "签名不一致",
    "SIGNATURE",
    "INSTALL_FAILED_SIGNATURE",
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
    "INSTALL_PARSE_FAILED",
    "安装包地址为空",
    "无法创建更新下载目录",
    "无法创建下载目录",
    "下载目录不可用",
    "下载已中断",
    "无法保存已下载的安装包",
    "安装包记录已失效",
    "无法启动应用下载服务",
)

/**
 * 失败分类：换 CDN 是否有意义。
 *
 * 只有「本地不可恢复」的致命错误才返回 [OtaCdnFailureKind.Fatal]，网络/HTTP/超时等
 * 一律 [OtaCdnFailureKind.RetryOnAnotherCdn]。
 *
 * 不用纯 message 匹配判定网络类错误：网络异常消息（如 "download interrupted"）可能
 * 偶发包含致命 token，误判会停止 CDN 切换、浪费整个更新流程。因此：
 * - 明确类型优先：CancellationException（用户取消/取消协程）、SecurityException（本地权限）
 *   判 Fatal；
 * - IOException 且消息命中本地致命 token（空间不足/目录/校验/记录/服务启动）判 Fatal；
 * - 其余 IOException / OkHttp 网络异常一律 RetryOnAnotherCdn。
 */
fun classifyOtaDownloadFailure(error: Throwable): OtaCdnFailureKind {
    if (error is CancellationException) return OtaCdnFailureKind.Fatal
    if (error is SecurityException) return OtaCdnFailureKind.Fatal
    val message = error.message.orEmpty()
    // 网络类异常（超时、连接中断、协议错误）换一条 CDN 通常有收益；即使消息里
    // 恰好含 token（如 "下载已中断" 被网络层复用），也不应据此停止切换。
    if (error !is IOException) return OtaCdnFailureKind.RetryOnAnotherCdn
    return if (FATAL_OTA_MESSAGE_TOKENS.any { message.contains(it, ignoreCase = true) }) {
        OtaCdnFailureKind.Fatal
    } else {
        OtaCdnFailureKind.RetryOnAnotherCdn
    }
}

/** 第 n 次切换 CDN 前的短退避，指数增长并封顶 2 秒。 */
fun otaCdnRetryDelayMs(attemptIndex: Int): Long =
    longArrayOf(250L, 500L, 1_000L, MAX_OTA_CDN_RETRY_DELAY_MS)[attemptIndex.coerceIn(0, 3)]

/**
 * 下载假死判定（纯函数）：本轮采样 [received] 与上次采样 [lastReceived] 相同，
 * 且距上次字节变化（[lastChangeMs]）已超过 [timeoutMs]。
 * 任务刚开始（尚未收到字节）时 lastReceived 传 0、lastChangeMs 传任务开始时间即可，
 * 让长期停在 0 字节的 PENDING/RUNNING 任务也能被快速判定，而不是等整体截止时间。
 */
fun isOtaDownloadStalled(
    received: Long,
    lastReceived: Long,
    lastChangeMs: Long,
    nowMs: Long,
    timeoutMs: Long = DEFAULT_OTA_DOWNLOAD_STALL_TIMEOUT_MS,
): Boolean = received == lastReceived && nowMs - lastChangeMs >= timeoutMs

data class OtaCdnAttempt(
    val label: String,
    val message: String?,
)

/**
 * 下载进度闸门：切换到备用 CDN 后新线路从 0% 重新计数，若直接透传会让进度条回跳。
 * 这里只向上游上报严格递增的值；未知进度（-1）不上报；100% 只上报一次，
 * 完成后忽略后续回调，避免系统下载器与应用内下载器重复触发安装态。
 */
class OtaDownloadProgressGate(private val emit: (Int) -> Unit) {
    private var lastReported = -1
    private var completed = false

    /**
     * 切换到备用 CDN 后新线路从 0% 重新计数。
     * 必须先重置 lastReported，否则新线路首次回调（如 3%）小于旧值会被丢弃，
     * 进度条会"卡住在旧值"直到新值超过它才跳变。
     */
    fun resetForNewCdn() {
        lastReported = -1
        completed = false
    }

    fun onProgress(value: Int) {
        if (completed) return
        val safe = value.coerceIn(-1, 100)
        if (safe == 100) {
            completed = true
            lastReported = 100
            emit(100)
            return
        }
        if (safe > lastReported) {
            lastReported = safe
            emit(safe)
        }
    }
}

/**
 * #321：把底层错误文案归一成用户可读的具体原因，优先给出签名冲突 / 存储空间不足的明确提示。
 * 返回 null 表示没有可明确归因的原因。下载失败汇总与安装失败上报共用，避免两处文案不一致。
 */
fun otaFailureReason(message: String?): String? {
    val text = message.orEmpty()
    return when {
        text.contains("签名冲突", ignoreCase = true) ||
            text.contains("签名不一致", ignoreCase = true) ||
            text.contains("SIGNATURE", ignoreCase = true) ||
            text.contains("INSTALL_FAILED_SIGNATURE", ignoreCase = true) ||
            text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
            text.contains("INSTALL_PARSE_FAILED", ignoreCase = true) ->
            "安装包签名冲突或解析失败（与已安装版本不一致，请先卸载旧版再安装）"
        text.contains("空间不足", ignoreCase = true) ||
            text.contains("INSUFFICIENT_SPACE", ignoreCase = true) ||
            text.contains("NO_SPACE", ignoreCase = true) ->
            "存储空间不足，请清理设备空间后重试"
        text.contains("校验失败", ignoreCase = true) -> "安装包校验未通过，请重新下载"
        text.contains("无进展", ignoreCase = true) -> "下载长时间无进展"
        text.contains("超时", ignoreCase = true) || text.contains("TIMEOUT", ignoreCase = true) ->
            "下载超时，请检查网络后重试"
        else -> null
    }
}

/**
 * 汇总错误信息：只输出线路数量与归类后的原因，不把原始 URL / 异常栈带给 UI 或日志。
 */
fun summarizeOtaCdnFailures(attempts: List<OtaCdnAttempt>): String {
    if (attempts.isEmpty()) return "更新下载失败，请检查网络后重试"
    val messages = attempts.mapNotNull { it.message }
    // #321：签名冲突 / 空间不足优先于「校验失败 / 超时」等泛化原因，保证提示具体可行动。
    val reason = messages.firstNotNullOfOrNull(::otaFailureReason)
    val prefix = "更新下载失败，已尝试 ${attempts.size} 个下载线路"
    return if (reason == null) "$prefix，请检查网络后重试" else "$prefix：$reason"
}
