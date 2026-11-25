package com.tvbox.app.data.network

/**
 * T1-09：按语义分档的超时预算，替代「全仓一个 10s」的复制粘贴默认值。
 *
 * 这里只定义**纯数据**，不持有 OkHttpClient 实例、不发起请求：
 * 调用方（探活 / 详情 / 海报 / 首帧 / OTA）必须显式选一个 [Scenario]，
 * 从而让「探活超时」不会被误记成「源慢」，也不会让 OTA 在 read 9.9s 处被砍。
 *
 * 单位统一为毫秒，便于 JVM 单测与 CI 的魔法数字门禁逐值核对。
 */
object Timeouts {

    /** 一次请求的 connect / read 两段预算；两者相加即该场景的总预算。 */
    data class Budget(val connectMs: Long, val readMs: Long) {
        init {
            require(connectMs > 0L) { "connectMs must be positive" }
            require(readMs > 0L) { "readMs must be positive" }
        }

        val totalMs: Long get() = connectMs + readMs
    }

    enum class Scenario { PROBE, DETAIL, POSTER, FIRST_FRAME, OTA }

    /** 失败语义：与评分 v2 的入参一一对应，探活超时只记 TIMEOUT，绝不记 SLOW。 */
    enum class FailureSemantic { TIMEOUT, CACHE, PLACEHOLDER, SWITCH_LINE, RESUME }

    /** 源探活 HEAD：1.5s + 2.5s = 4s，最快判定死源，首页不白屏 10 秒。 */
    val PROBE = Budget(1_500L, 2_500L)

    /** 详情 / 选集：2.5s + 5s = 7.5s，失败走缓存而不是让 UI 转圈。 */
    val DETAIL = Budget(2_500L, 5_000L)

    /** 海报：2s + 4s = 6s，失败显示占位图 + 淡入。 */
    val POSTER = Budget(2_000L, 4_000L)

    /** 播放首帧：3s + 6s = 9s，到首个 onRenderedFirstFrame 为止，超时换线路。 */
    val FIRST_FRAME = Budget(3_000L, 6_000L)

    /** OTA 下载：5s + 15s 无进度，禁止整包重下（断点续传由上层负责）。 */
    val OTA = Budget(5_000L, 15_000L)

    /** 播放中缓冲：12s 无 byte 推进才允许换源；不是 connect/read 两段式。 */
    const val BUFFER_STALL_MS: Long = 12_000L

    fun forScenario(scenario: Scenario): Budget = when (scenario) {
        Scenario.PROBE -> PROBE
        Scenario.DETAIL -> DETAIL
        Scenario.POSTER -> POSTER
        Scenario.FIRST_FRAME -> FIRST_FRAME
        Scenario.OTA -> OTA
    }

    fun failureSemantic(scenario: Scenario): FailureSemantic = when (scenario) {
        Scenario.PROBE -> FailureSemantic.TIMEOUT
        Scenario.DETAIL -> FailureSemantic.CACHE
        Scenario.POSTER -> FailureSemantic.PLACEHOLDER
        Scenario.FIRST_FRAME -> FailureSemantic.SWITCH_LINE
        Scenario.OTA -> FailureSemantic.RESUME
    }
}
