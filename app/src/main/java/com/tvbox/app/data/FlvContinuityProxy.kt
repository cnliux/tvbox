package com.tvbox.app.data

import android.util.Log
import com.tvbox.app.data.live.LiveContinuityRelay
import com.tvbox.app.data.live.LiveLine
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
 * 现在的优先级：
 *  1. **Kotlin 本机中继**（[LiveContinuityRelay]）——不依赖重新编译 Go 侧 AAR，
 *     可随时改参数；换了新签名就续拉，播放器只看到一条永不结束的本地 FLV；
 *  2. Go 侧代理（`platform_live_go` 的 gomobile 实现）——Kotlin 中继启动失败时兜底，
 *     保留原有现场调参能力。
 *
 * 只对虎牙/斗鱼启用是刻意的产品约束：B 站/抖音/快手等平台的签名与会话语义不同，
 * 把这两家的假设套过去反而会引入新问题。
 */
object FlvContinuityProxy {
    private const val LOG_TAG = "TVBoxPlatformLive"

    private val supportedSites = setOf("huya", "douyu")

    fun shouldWrap(site: String, protocol: String): Boolean =
        protocol.equals("flv", ignoreCase = true) && site.lowercase() in supportedSites

    /**
     * 启动本机中继；返回 null 表示两条路径都失败，调用方回退为直连平台地址。
     *
     * [renew] 用于续流时拿新签名线路（在后台线程同步调用），
     * 由 ViewModel 走平台解析得到，避免代理内部再依赖 Go 桥接。
     */
    fun start(
        site: String,
        roomId: String,
        candidateIndex: Int,
        url: String,
        headers: Map<String, String>,
        renew: ((Int) -> LiveLine?)? = null,
    ): LiveProxyHandle? {
        // 顺序很重要：Go 代理是经过 20 分钟 × 6 房间真机验证的成熟实现
        // （预建双连接 + 指纹去重 + 进度看门狗），必须保持为默认路径。
        // Kotlin 中继目前在真机上会在 250ms 内让播放器报 ERROR_CODE_IO_UNSPECIFIED
        // （JVM 端到端测试通过，差异在 Android 的 HTTP 响应处理上，仍在排查），
        // 因此只能在 Go 代理**不可用**时兜底，绝不能抢在前面造成回归。
        startGoProxy(site, roomId, candidateIndex, url, headers)?.let { return it }
        if (renew == null) return null
        Log.w(LOG_TAG, "【连续中继】Go 代理不可用，尝试 Kotlin 中继兜底")
        return LiveContinuityRelay.start(
            site = site,
            roomId = roomId,
            line = LiveLine(url = url, cdn = "", headers = headers),
            renew = renew,
        )?.also { Log.d(LOG_TAG, "【连续中继】Kotlin 中继已启用 ${it.localUrl()}") }
    }

    /** Go 侧代理（历史实现，保留作为兜底）。 */
    private fun startGoProxy(
        site: String,
        roomId: String,
        candidateIndex: Int,
        url: String,
        headers: Map<String, String>,
    ): LiveProxyHandle? = runCatching {
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

    /** gomobile 句柄适配到统一接口。 */
    private class GoProxyHandle(private val handle: FlvProxyHandle) : LiveProxyHandle {
        override fun localUrl(): String = handle.localUrl()
        override fun stats(): String = runCatching { handle.stats() }.getOrDefault("{}")
        override fun close() = runCatching { handle.close() }.let { }
    }
}
