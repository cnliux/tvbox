package com.tvbox.app.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * T1-02：明文 HTTP 的「按域」策略。
 *
 * Android 的 `network_security_config.xml` 是构建期静态文件，无法枚举用户运行时导入的源域。
 * 因此策略分两层：
 * 1. NSC `domain-config` 把内置域名（OTA / 豆瓣 / GitHub / AI 等）强制 HTTPS，默认禁止明文；
 * 2. 本守卫在应用自己的网络出口再做一次运行期判定：内置域名一律拒绝 http，用户源允许 http
 *    并计入会话计数（设置页可显示「本次会话 N 个源走明文 / M 个走 TLS」）。
 *
 * 纯 Kotlin 判定函数 + OkHttp 拦截器，不依赖 Android 框架，便于 JVM 单测。
 */
object CleartextPolicy {

    /**
     * 应用主动请求的内置域名（后缀匹配，含子域）。这些域名不允许明文，
     * 与 `res/xml/network_security_config.xml` 的 domain-config 保持同一份真源口径。
     */
    val BUILTIN_HOST_SUFFIXES: List<String> = listOf(
        "douban.com",
        "doubanio.com",
        "360kan.com",
        "360.cn",
        "github.com",
        "githubusercontent.com",
        "jsdelivr.net",
        "gh-proxy.com",
        "gh-proxy.org",
        "ghproxy.net",
    )

    /** host 是否属于内置域名（大小写不敏感，尾部点忽略）。 */
    fun isBuiltinHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        return BUILTIN_HOST_SUFFIXES.any { suffix ->
            normalized == suffix || normalized.endsWith(".$suffix")
        }
    }

    /** 解析 URL 的 host；非法 URL 返回 null（调用方按最保守策略处理）。 */
    fun hostOf(url: String): String? =
        runCatching { url.toHttpUrl().host }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 内置域名必须 HTTPS；用户导入源允许明文。 */
    fun requiresHttps(url: String): Boolean = hostOf(url)?.let(::isBuiltinHost) ?: false

    /** 明文是否被放行：仅当用户源且确实是 http 时允许。 */
    fun allowsCleartext(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) && !requiresHttps(url)
}

/** 会话统计快照。 */
data class CleartextStats(val cleartext: Int, val tls: Int, val blocked: Int)

/**
 * 网络出口守卫：拒绝内置域名的明文请求，并对明/密请求计数。
 * 挂在 [HttpClients.default] / [HttpClients.probe] 上，所有仓库共用。
 */
class CleartextGuard : Interceptor {
    private val cleartextCount = AtomicInteger(0)
    private val tlsCount = AtomicInteger(0)
    private val blockedCount = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        when {
            request.url.scheme.equals("http", ignoreCase = true) -> {
                if (CleartextPolicy.isBuiltinHost(host)) {
                    blockedCount.incrementAndGet()
                    throw IOException("内置域名禁止明文 HTTP：$host（T1-02）")
                }
                cleartextCount.incrementAndGet()
            }
            request.url.scheme.equals("https", ignoreCase = true) -> tlsCount.incrementAndGet()
        }
        return chain.proceed(request)
    }

    fun snapshot(): CleartextStats = CleartextStats(
        cleartext = cleartextCount.get(),
        tls = tlsCount.get(),
        blocked = blockedCount.get(),
    )
}
