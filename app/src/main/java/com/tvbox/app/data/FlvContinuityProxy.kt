package com.tvbox.app.data

import android.util.Log
import com.tvbox.app.util.logSafeText
import org.json.JSONObject
import platformlive.FlvProxyHandle
import platformlive.Platformlive

/**
 * 平台直播「连续 FLV 代理」。
 *
 * 虎牙 CDN 对单次 HTTP-FLV 拉流有约 2 分钟的会话上限（实测 120~137 秒被服务端
 * 正常关闭，同签名的 HLS 地址约 142 秒后也会 403），直接播放平台地址时播放器
 * 每 2 分钟必然经历一次 ENDED → 重新解析 → 重新 prepare 的可见断流。
 *
 * 这里把播放地址换成 Go 侧（platform_live_go）的本机代理：上游被关闭时代理会
 * 立刻重新解析拿新签名续拉，并把新一段的时间戳接在上一帧之后，播放器侧只看到
 * 一条永不结束的本地 FLV，因此不再出现 ENDED。
 */
object FlvContinuityProxy {
    private const val LOG_TAG = "TVBoxPlatformLive"

    /**
     * 只有虎牙 / 斗鱼进入连续 FLV 代理，这是刻意的产品约束，不是遗漏：
     *  - 虎牙 ~120s 必被 CDN 关流，需要计划内预建双连接 + 归零时间轴平移；
     *  - 斗鱼是 expire 租约语义，需要 expire=0 补丁 + 主动续期 + 同轴时间轴；
     *  - 其余平台（B站/抖音/快手等）**不进代理**，因此根本不会碰到虎牙或
     *    斗鱼的任何专属假设，避免把实测结论错误套到第三个平台上。
     *
     * Go 侧 siteTuningFor 的第三档 `generic-live` 是「防御性回退」：仅在
     * 将来放开白名单、或有自定义源显式指定代理时才会启用；当前生产路径不可达，
     * 不要把它当成「其它平台正在用的策略」。
     */
    private val supportedSites = setOf("huya", "douyu")

    fun shouldWrap(site: String, protocol: String): Boolean =
        protocol.equals("flv", ignoreCase = true) && site.lowercase() in supportedSites

    /**
     * 启动本机代理；失败时返回 null，调用方回退为直连平台地址。
     * [candidateIndex] 与 [url]/[headers] 复用已解析结果，避免重复请求平台接口。
     */
    fun start(
        site: String,
        roomId: String,
        candidateIndex: Int,
        url: String,
        headers: Map<String, String>,
    ): FlvProxyHandle? = runCatching {
        Platformlive.startFlvProxy(
            site,
            roomId,
            candidateIndex.toLong(),
            url,
            JSONObject(headers.toMap()).toString(),
        )
    }.getOrElse { error ->
        Log.w(LOG_TAG, "【连续代理】启动失败，回退直连：${logSafeText(error.message)}")
        null
    }
}
