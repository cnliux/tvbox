package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException

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

/** 默认用于 GitHub Release APK 的代理 CDN（按优先级排列）。 */
val DEFAULT_OTA_APK_CDN_HOSTS: List<String> = listOf(
    "ghfast.top",
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
 * 生成更新清单候选。前两个保持原有顺序（ghfast 主源 → GitHub raw 备源），
 * 之后追加多个 CDN 兜底；jsDelivr 系对分支有缓存，只作兜底，
 * versionCode 比对保证旧缓存不会触发降级安装。
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
        OtaCdnCandidate("manifest-ghfast", "ghfast", "https://ghfast.top/$raw"),
        OtaCdnCandidate("manifest-raw", "GitHub raw", raw),
        OtaCdnCandidate("manifest-gh-proxy", "gh-proxy", "https://gh-proxy.com/$raw"),
        OtaCdnCandidate("manifest-ghproxy-net", "ghproxy.net", "https://ghproxy.net/$raw"),
        OtaCdnCandidate("manifest-jsdelivr", "jsDelivr", "https://cdn.jsdelivr.net$jsdelivrPath"),
        OtaCdnCandidate("manifest-fastly", "Fastly jsDelivr", "https://fastly.jsdelivr.net$jsdelivrPath"),
        OtaCdnCandidate("manifest-gcore", "Gcore jsDelivr", "https://gcore.jsdelivr.net$jsdelivrPath"),
    )
}

/**
 * 按本机健康度排序候选。无任何记录时严格保持声明顺序（主源优先不变）；
 * 连续失败越多惩罚越大，冷却期后惩罚衰减。成功次数只用于状态展示，
 * 不参与排序加权，避免「成功过的线路」越过从未失败的官方源造成行为漂移。
 */
fun rankOtaCdnCandidates(
    candidates: List<OtaCdnCandidate>,
    health: Map<String, OtaCdnHealth>,
    nowMs: Long,
    cooldownMs: Long = DEFAULT_OTA_CDN_COOLDOWN_MS,
): List<OtaCdnCandidate> = candidates
    .mapIndexed { index, candidate ->
        val record = health[candidate.id] ?: OtaCdnHealth()
        val failureAgeMs = if (record.lastFailureAtMs > 0L) nowMs - record.lastFailureAtMs else 0L
        val decay = if (record.consecutiveFailures > 0 && failureAgeMs >= cooldownMs) 0.2 else 1.0
        val penalty = minOf(record.consecutiveFailures, 6) * 100.0 * decay
        RankedOtaCandidate(score = penalty, index = index, candidate = candidate)
    }
    .sortedWith(compareBy<RankedOtaCandidate> { it.score }.thenBy { it.index })
    .map { it.candidate }

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
    "安装包地址为空",
    "无法创建更新下载目录",
    "无法创建下载目录",
    "下载目录不可用",
    "下载已中断",
    "无法保存已下载的安装包",
    "安装包记录已失效",
    "无法启动应用下载服务",
)

fun classifyOtaDownloadFailure(error: Throwable): OtaCdnFailureKind {
    if (error is CancellationException) return OtaCdnFailureKind.Fatal
    val message = error.message.orEmpty()
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
 * 汇总错误信息：只输出线路数量与归类后的原因，不把原始 URL / 异常栈带给 UI 或日志。
 */
fun summarizeOtaCdnFailures(attempts: List<OtaCdnAttempt>): String {
    if (attempts.isEmpty()) return "更新下载失败，请检查网络后重试"
    val messages = attempts.mapNotNull { it.message }
    val reason = when {
        messages.any { it.contains("校验失败", ignoreCase = true) } -> "，安装包校验未通过"
        messages.any { it.contains("空间不足", ignoreCase = true) } -> "，存储空间不足"
        messages.any { it.contains("无进展", ignoreCase = true) } -> "，下载长时间无进展"
        messages.any { it.contains("超时", ignoreCase = true) } -> "，下载超时"
        else -> ""
    }
    return "更新下载失败，已尝试 ${attempts.size} 个下载线路$reason，请检查网络后重试"
}
