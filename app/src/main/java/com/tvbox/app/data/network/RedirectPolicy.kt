package com.tvbox.app.data.network

import java.net.URI

/**
 * T1-03：302 跟随的跨协议 / 跨站降级策略。
 *
 * OkHttp 默认跟随跳转，而 cleartext 放行时 HTTPS → HTTP 会被无声跟随：
 * TLS 里发出的 Referer/UA 被明文带到下一跳，按域 UA 策略也在跳转后失效。
 * 这里把「要不要记一笔、要不要重算 UA、要不要弹提示」收敛成纯函数，
 * 由 networkInterceptor 调用；不持有 OkHttp 实例，便于 JVM 单测。
 */
object RedirectPolicy {

    enum class Risk {
        /** 完全同源：无需处理。 */
        SAME_ORIGIN,
        /** 同一 eTLD+1 的子域：UA 仍可复用。 */
        CROSS_SUBDOMAIN,
        /** 跨站（eTLD+1 变化）：必须重算 UaScope，且 UA/Referer 已跨站。 */
        CROSS_SITE,
        /** 跨协议降级（HTTPS → HTTP）：隐私风险最高，必须记账 + 提示。 */
        DOWNGRADE,
    }

    data class Hop(val fromUrl: String, val toUrl: String)

    /** 简化 eTLD+1：最后两段；公网后缀（co.uk 等）由调用方的 UaScope 覆盖表兜底。 */
    private fun registrable(host: String): String {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return ""
        if (isIpLiteral(normalized)) return normalized
        val labels = normalized.split('.').filter { it.isNotEmpty() }
        return if (labels.size >= 2) "${labels[labels.size - 2]}.${labels.last()}" else normalized
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""").matches(host)

    private fun parts(url: String): Pair<String, String>? {
        val uri = runCatching { URI.create(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme.isEmpty() || host.isEmpty()) return null
        return scheme to host
    }

    fun classify(hop: Hop): Risk {
        val from = parts(hop.fromUrl) ?: return Risk.CROSS_SITE // 解析失败一律按最严处理
        val to = parts(hop.toUrl) ?: return Risk.CROSS_SITE
        // 跨协议降级与 host 是否变化无关：同 host 的 HTTPS→HTTP 同样把 Referer 泄露成明文。
        if (from.first == "https" && to.first == "http") return Risk.DOWNGRADE
        if (from.second == to.second) return Risk.SAME_ORIGIN
        return if (registrable(from.second) == registrable(to.second)) {
            Risk.CROSS_SUBDOMAIN
        } else {
            Risk.CROSS_SITE
        }
    }

    /** 只有降级或跨站才需要弹「本次已放行 N 次明文跳转」并计数。 */
    fun mustWarn(hop: Hop): Boolean = classify(hop) in setOf(Risk.DOWNGRADE, Risk.CROSS_SITE)

    /** 跨子域/跨站/降级都必须按目标 host 重算 UA，否则落回默认 UA 被源站 403。 */
    fun mustRecomputeUa(hop: Hop): Boolean = classify(hop) != Risk.SAME_ORIGIN
}
