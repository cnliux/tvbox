package com.tvbox.app.data.live

/**
 * 本机连续 FLV 中继的句柄。
 *
 * 播放器只连 [localUrl] 这一条本机地址，上游 CDN 的到期/断流全部由中继内部
 * 处理，播放器侧不会再收到 ENDED。
 */
interface LiveProxyHandle {
    /** 给播放器用的本机地址（127.0.0.1 的临时端口）。 */
    fun localUrl(): String

    /**
     * 一条 JSON 统计串，供播放页「播放健康」日志与调试面板读取。
     * 字段与历史 Go 代理保持一致（缺失字段由 `opt` 返回 null，不影响解析）。
     */
    fun stats(): String

    /** 停止中继并关闭上游连接；可重复调用。 */
    fun close()
}

/**
 * 一条可播放线路。
 *
 * [headers] 已按线路归一化（UA/Referer/Origin 与签名 ctype 保持一致），
 * 中继与播放器使用同一份，避免 CDN 因 UA 与签名不符返回 403 或十几秒后掐流。
 */
data class LiveLine(
    val url: String,
    val cdn: String = "",
    val headers: Map<String, String> = emptyMap(),
)
