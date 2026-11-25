package com.tvbox.app.domain

import java.util.ArrayDeque
import java.util.Locale

/**
 * T3-19：体验指标最小管道（不上 Firebase，TV 盒子 GMS 缺失率太高）。
 *
 * 隐私红线：`tag` 是 sealed 枚举 ⇒ **编译期就不可能把片名/源 URL/搜索词塞进遥测**。
 * [record] 只接受枚举 + 数字，没有 String 参数；[encode] 只输出枚举名和数字。
 * 上传（仅 Wi-Fi + 充电 + 10% 采样 + gzip ≤8KB）由调用方另行实现，本类只负责采集与序列化。
 */
sealed class MetricTag(val key: String) {
    object TtfpP50 : MetricTag("ttfp_p50")
    object TtfpP95 : MetricTag("ttfp_p95")
    object RebufferRatio : MetricTag("rebuffer_ratio")
    object SeekP95Ok : MetricTag("seek_p95_ok")
    object SourceDeadRate : MetricTag("source_dead_rate")
    object RemoteKeyLatencyP95 : MetricTag("remote_key_latency_p95")
    object OomKillPer1kSess : MetricTag("oom_kill_per_1k_sess")
    object MemoryTier : MetricTag("memory_tier")

    /** 正交维度：只准枚举值，禁止自由文本。 */
    sealed class Dim(val value: String) {
        object Tight : Dim("TIGHT")
        object Critical : Dim("CRITICAL")
        object Rich : Dim("RICH")
    }

    companion object {
        /** lazy：避免 sealed 类静态初始化期间引用嵌套 object 造成半初始化值。 */
        val ALL: List<MetricTag> by lazy {
            listOf(
                TtfpP50, TtfpP95, RebufferRatio, SeekP95Ok,
                SourceDeadRate, RemoteKeyLatencyP95, OomKillPer1kSess, MemoryTier,
            )
        }
    }
}

/** 一条采集样本。[tag] 只能是 sealed 枚举名，[dimension] 只能是枚举值。 */
data class Tick(
    val t: Long,
    val k: String,
    val v: Int,
    val dimension: String? = null,
)

/** 环形缓冲：最多 [capacity] 条，超出丢弃最旧一条。 */
class TelemetryBuffer(private val capacity: Int = 200) {
    private val ticks = ArrayDeque<Tick>(capacity)

    init {
        require(capacity > 0) { "capacity 必须为正" }
    }

    @Synchronized
    fun record(tag: MetricTag, value: Int, atMs: Long, dimension: MetricTag.Dim? = null) {
        if (ticks.size == capacity) ticks.removeFirst()
        ticks.addLast(Tick(atMs, tag.key, value, dimension?.value))
    }

    @Synchronized
    fun snapshot(): List<Tick> = ticks.toList()

    @Synchronized
    fun clear() {
        ticks.clear()
    }

    @Synchronized
    fun size(): Int = ticks.size
}

/** 全局采集开关与唯一缓冲。关掉时立刻清缓冲，满足「关掉即不留数据」。 */
object ExperienceTelemetry {
    private val buffer = TelemetryBuffer()
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) buffer.clear()
        }

    fun record(tag: MetricTag, value: Int, atMs: Long, dimension: MetricTag.Dim? = null) {
        if (enabled) buffer.record(tag, value, atMs, dimension)
    }

    fun snapshot(): List<Tick> = if (enabled) buffer.snapshot() else emptyList()

    fun clear() = buffer.clear()

    /** 只输出枚举名和数字；不含任何用户内容。 */
    fun encode(): String = if (!enabled) "" else encodeTicks(buffer.snapshot())
}

/** 纯序列化函数，便于单测锁定「无自由文本」契约。 */
fun encodeTicks(ticks: List<Tick>): String = buildString {
    append('[')
    ticks.forEachIndexed { index, tick ->
        if (index > 0) append(',')
        append("{\"t\":").append(tick.t)
        append(",\"k\":\"").append(safeKey(tick.k)).append('"')
        append(",\"v\":").append(tick.v)
        tick.dimension?.let { dimension ->
            append(",\"tag\":\"").append(safeKey(dimension)).append('"')
        }
        append('}')
    }
    append(']')
}

/** 白名单：只允许已知枚举 key / value 通过；未知值降级为空串而不是透传原文。 */
private val ALLOWED_KEYS: Set<String> = setOf(
    "ttfp_p50", "ttfp_p95", "rebuffer_ratio", "seek_p95_ok",
    "source_dead_rate", "remote_key_latency_p95", "oom_kill_per_1k_sess", "memory_tier",
    "TIGHT", "CRITICAL", "RICH",
)

private fun safeKey(value: String): String =
    if (value in ALLOWED_KEYS) value.lowercase(Locale.ROOT) else ""
