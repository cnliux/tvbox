package com.tvbox.app.data

import android.util.Log
import com.tvbox.app.data.live.LiveProxyHandle
import com.tvbox.app.util.logSafeText
import org.json.JSONObject
import platformlive.FlvProxyHandle
import platformlive.Platformlive

/**
 * 平台直播「连续 FLV 代理」的统一入口。
 *
 * 背景（实测）：虎牙 CDN 对单次 HTTP-FLV 拉流有约 120 秒的会话上限
 * （房间 11342412 实测 120.7s 被服务端正常关闭），斗鱼则用 `expire` 租约到期断流。
 * 播放器直接用平台地址时，每次都会经历 ENDED → 重新解析 → 重新 prepare 的可见断流。
 *
 * 生产路径只有一条：**Go 侧代理**（`platform_live_go` 的 gomobile 实现），
 * 经真机长跑验证（预建双连接 + 指纹去重 + 自适应扩窗 + 音频回拉 + 进度看门狗）。
 * 启动失败时返回 null，由调用方退回直连平台地址——直连只是每两分钟重连一次，
 * 仍可正常播放。
 *
 * 历史上曾有一条 Kotlin 本机中继作为兜底，但它在本机 JVM 测试通过、Android 真机
 * 却必定报 `ERROR_CODE_IO_UNSPECIFIED`，从未验证可用；保留它等于把"兜底"做成
 * "一定失败"，因此已整体移除（见 CHANGELOG v1.0.19）。
 *
 * 只对虎牙/斗鱼启用是刻意的产品约束：B 站/抖音/快手等平台的签名与会话语义不同，
 * 把这两家的假设套过去反而会引入新问题。
 */
object FlvContinuityProxy {
    private const val LOG_TAG = "TVBoxPlatformLive"

    private val supportedSites = setOf("huya", "douyu")

    /**
     * 判断是否需要套连续 FLV 代理。
     *
     * [url] 是双保险：即使上游 protocol 字段异常，也绝不把 HLS/m3u8 地址
     * 交给只处理 FLV tag 的 Go 代理（否则会启动失败并误报"回退直连"）。
     */
    fun shouldWrap(site: String, protocol: String, url: String = ""): Boolean {
        if (!protocol.equals("flv", ignoreCase = true) || site.lowercase() !in supportedSites) return false
        if (url.contains("m3u8", ignoreCase = true)) return false
        // 第三方代理解析地址（路径形如 /huya/房间号、/douyu/房间号，无 .flv 后缀、无官方签名参数）
        // 本身就是稳定流，不经过虎牙/斗鱼官方 CDN 的签名租约与会话上限；再套本机 FLV 续流代理
        // 会把它当成普通 CDN 地址去做签名解析/续拉，反而打断第三方流。直接放行直连。
        if (looksLikeThirdPartyProxy(url)) return false
        return url.isBlank() || url.contains("flv", ignoreCase = true)
    }

    /**
     * 判断地址是否为第三方代理解析地址：路径以 `/huya/` 或 `/douyu/` 开头，
     * 且不含 `.flv` 后缀与官方签名参数（wsSecret/wsTime）。
     */
    private fun looksLikeThirdPartyProxy(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val path = runCatching { java.net.URI(trimmed).path.orEmpty() }.getOrDefault("")
        val isSitePath = path.startsWith("/huya/", ignoreCase = true) ||
            path.startsWith("/douyu/", ignoreCase = true)
        if (!isSitePath) return false
        return !path.endsWith(".flv", ignoreCase = true) &&
            !trimmed.contains("wsSecret", ignoreCase = true) &&
            !trimmed.contains("wsTime", ignoreCase = true)
    }

    /**
     * 启动本机代理；返回 null 表示启动失败，调用方回退为直连平台地址。
     */
    fun start(
        site: String,
        roomId: String,
        candidateIndex: Int,
        url: String,
        headers: Map<String, String>,
    ): LiveProxyHandle? {
        // Go 代理是经过真机长跑验证的成熟实现（预建双连接 + 指纹去重 + 进度看门狗），
        // 必须保持为唯一路径；启动异常时返回 null 交给调用方直连。
        return runCatching {
            Platformlive.startFlvProxy(
                site,
                roomId,
                candidateIndex.toLong(),
                url,
                JSONObject(headers.toMap()).toString(),
            )?.let(::GoProxyHandle)
        }.getOrElse { error ->
            Log.w(LOG_TAG, "【连续代理】启动失败，回退直连：${logSafeText(error.message)}")
            null
        }
    }

    /** gomobile 句柄适配到统一接口。 */
    private class GoProxyHandle(private val handle: FlvProxyHandle) : LiveProxyHandle {
        override fun localUrl(): String = handle.localUrl()
        override fun stats(): String = runCatching { handle.stats() }.getOrDefault("{}")
        override fun close() = runCatching { handle.close() }.let { }
    }
}
