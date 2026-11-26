package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import java.net.URI

/**
 * T2-16：探针统一调度入口——全仓的「源级主动探针」请求只允许经由本类发出。
 *
 * 此前 ViewModel 的后台体检 / 用户一键测速 / 详情页线路测速各自独立发探针、
 * 各自判冷却，对刚失败的源反复探测会把活源自己探死。调度规则收敛为一个状态机：
 * - [DEBOUNCE_MS] 防抖：同一条源 400ms 内的两次后台探针合并为一次，第二次被抑制不发请求；
 * - 同 host 并发 = 1：后台探针（[probe]）同 host 忙时被抑制，绝不堆积并行请求；
 *   用户主动探针（[probeNow]）在同 host 上排队等待——用户意图不丢弃，只串行；
 * - 首页冷启动：主动探测至多 [ProbeCooldown.MAX_COLD_START_PROBES] 个源（[planColdStartBatch]），
 *   其余懒探（用户滚到/点到才探）；
 * - 冷却：DEAD 后 15min、SLOW 后 5min 不再探（[ProbeCooldown]）；探测失败只降 SLOW，
 *   DEAD 必须由 [reportUserPlaybackFailure] 累计 2 次用户真实播放失败才落章。
 *
 * 探针请求统一携带专用 UA [UaScope.ProbeUserAgent]（`TVBox-Probe/1.0 (+healthcheck)`），
 * 租约由 [ProbeUaLease] 持有，并发探针不会提前还原成播放 UA。
 *
 * 不在本类范围：直播线路级探测（MIME / TTFB，LiveStreamProbe / LiveLineLatency）
 * 与 OTA CDN 探活（AppUpdateRepository）——它们不参与源级 DEAD/SLOW 判定，
 * 各有自己的预算口径，不收敛到这里。
 */
class ProbeScheduler(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    companion object {
        /** 防抖窗口：同一条源在窗口内的两次后台探针合并为一次。 */
        const val DEBOUNCE_MS: Long = 400L
    }

    /** 探针被抑制的原因：没有发出任何网络请求。 */
    enum class Suppression {
        /** 400ms 防抖窗口内的重复探针。 */
        DEBOUNCE,

        /** 处于 DEAD / SLOW 冷却窗口。 */
        COOLING_DOWN,

        /** 冷启动预算（最多 3 个源）已耗尽。 */
        COLD_START_BUDGET,

        /** 同 host 已有探针在途（后台探针不排队）。 */
        HOST_BUSY,
    }

    /** 探针请求的结果：[Probed] 实际发出了请求；[Suppressed] 被调度器抑制。 */
    sealed interface Outcome {
        /** 探针已执行；[latencyMs] 为 null 表示探测失败（降 SLOW，绝不落 DEAD）。 */
        data class Probed(val latencyMs: Long?) : Outcome

        /** 被抑制：本轮没有发出请求，调用方不应把它记作「探测失败」。 */
        data class Suppressed(val reason: Suppression) : Outcome

        /** 转成测速表值：Probed 取结果，Suppressed 返回 null（调用方须自行区分二者）。 */
        fun asLatencyOrNull(): Long? = when (this) {
            is Probed -> latencyMs
            is Suppressed -> null
        }
    }

    /**
     * 后台 / 周期性探针：冷却 → 防抖 → 冷启动预算 → 同 host 并发 全部门禁。
     *
     * @param sourceId 视频源（apiLine）id，冷却与防抖的维度。
     * @param host 探针目标 host（并发 = 1 的维度，取 eTLD+1 口径见 [probeHostOf]）。
     * @param coldStart 是否首页冷启动批次：是则受 [ProbeCooldown.MAX_COLD_START_PROBES] 预算约束。
     * @param execute 真正的探测执行体（如 `repository.testSpeed(id)`），返回 null 表示探测失败。
     */
    suspend fun probe(
        sourceId: String,
        host: String,
        coldStart: Boolean = false,
        execute: suspend () -> Long?,
    ): Outcome {
        val gate = gateFor(host)
        val now = nowMs()
        val blocked: Suppression? = synchronized(lock) {
            when {
                ProbeCooldown.isCoolingDown(cooldownStates[sourceId], now) -> Suppression.COOLING_DOWN
                lastFiredAtMs[sourceId]?.let { now - it < DEBOUNCE_MS } == true -> Suppression.DEBOUNCE
                coldStart && coldStartRemaining == 0 -> Suppression.COLD_START_BUDGET
                !gate.tryReserve() -> Suppression.HOST_BUSY
                else -> {
                    lastFiredAtMs[sourceId] = now
                    if (coldStart) coldStartRemaining--
                    null
                }
            }
        }
        if (blocked != null) return Outcome.Suppressed(blocked)
        return runProbed(sourceId, gate, execute)
    }

    /**
     * 用户主动探针（显式点测速）：不适用冷却 / 防抖 / 冷启动预算——用户意图优先，
     * DEAD 源也可以立即复核；同 host 并发 = 1 仍排队等待（挂起直到拿到 host 租约）。
     *
     * @return 探针结果，null 表示探测失败。probeNow 不会被抑制（只排队），
     *         因此 [ProbeScheduler.Outcome.asLatencyOrNull] 的口径与返回值语义一致。
     */
    suspend fun probeNow(sourceId: String, host: String, execute: suspend () -> Long?): Long? {
        val gate = gateFor(host)
        gate.reserve()
        return runProbed(sourceId, gate, execute).asLatencyOrNull()
    }

    /**
     * 冷启动批次计划：至多 [ProbeCooldown.MAX_COLD_START_PROBES] 个源（按调用方顺序 = 探测优先级），
     * 冷却中的源被剔除。返回本轮真正要探的 id 列表；非冷启动轮用 [isReady] 做同口径预筛。
     */
    fun planColdStartBatch(sourceIds: List<String>): List<String> = synchronized(lock) {
        val now = nowMs()
        sourceIds
            .filter { ProbeCooldown.shouldProbe(cooldownStates[it], now) }
            .take(coldStartRemaining)
    }

    /** 干查：该源当前是否允许后台探针（不在冷却窗口内，不消耗任何预算）。 */
    fun isReady(sourceId: String): Boolean = synchronized(lock) {
        ProbeCooldown.shouldProbe(cooldownStates[sourceId], nowMs())
    }

    /**
     * 上报一次用户真实播放失败：累计到 [ProbeCooldown.DEAD_USER_FAILURE_THRESHOLD] 次落 DEAD，
     * 其后 [ProbeCooldown.DEAD_COOLDOWN_MS] 内所有后台探针被抑制。
     * 探测失败本身永远只降 SLOW，不落 DEAD（「只读不改判」闸门）。
     */
    fun reportUserPlaybackFailure(sourceId: String) {
        synchronized(lock) {
            val now = nowMs()
            cooldownStates[sourceId] = ProbeCooldown.onUserPlaybackFailure(cooldownStates[sourceId], now)
        }
    }

    /** 该源当前评级（可观测）；null 表示从未探过。 */
    fun gradeOf(sourceId: String): ProbeCooldown.Grade? = synchronized(lock) {
        cooldownStates[sourceId]?.grade
    }

    /** 实际发出探针：探针 UA 租约 + 执行 + 评级回写，host 租约在 finally 里必释放。 */
    private suspend fun runProbed(
        sourceId: String,
        gate: HostGate,
        execute: suspend () -> Long?,
    ): Outcome {
        val firedAt = markFired(sourceId)
        ProbeUaLease.acquire(UaScope.ProbeUserAgent)
        val latency: Long? = try {
            try {
                execute()
            } catch (cancelled: CancellationException) {
                // 切页 / 取消不能当成「探测失败」写评级，更不能吞掉取消。
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } finally {
            ProbeUaLease.release()
            gate.release()
        }
        updateGrade(sourceId, firedAt, latency)
        return Outcome.Probed(latency)
    }

    /** 记录发射时刻（防抖窗口锚点）；返回时刻供评级回写用同一口径。 */
    private fun markFired(sourceId: String): Long {
        val now = nowMs()
        synchronized(lock) { lastFiredAtMs[sourceId] = now }
        return now
    }

    private fun updateGrade(sourceId: String, firedAt: Long, latency: Long?) {
        synchronized(lock) {
            val previous = cooldownStates[sourceId]
            cooldownStates[sourceId] = if (latency == null) {
                ProbeCooldown.onProbeFailure(previous, firedAt)
            } else {
                ProbeCooldown.onProbeSuccess(previous, firedAt)
            }
        }
    }

    private val lock = Any()
    /** 冷却评级状态表（原 ViewModel 的 probeCooldownStates 收敛到这里）。 */
    private val cooldownStates = mutableMapOf<String, ProbeCooldown.State>()
    private val lastFiredAtMs = mutableMapOf<String, Long>()
    private val hostGates = mutableMapOf<String, HostGate>()
    private var coldStartRemaining = ProbeCooldown.MAX_COLD_START_PROBES

    private fun gateFor(host: String): HostGate = synchronized(lock) {
        hostGates.getOrPut(host) { HostGate() }
    }

    /** 同 host 并发 = 1：后台探针非阻塞抢租约（忙则抑制），用户探针挂起排队。 */
    private class HostGate {
        private val semaphore = Semaphore(1)

        fun tryReserve(): Boolean = semaphore.tryAcquire()

        suspend fun reserve() {
            semaphore.acquire()
        }

        fun release() {
            semaphore.release()
        }
    }
}

/** 无法解析出 host 的源 URL 统一归到这个并发组（保守串行，不并行打）。 */
const val UNRESOLVED_PROBE_HOST = "unresolved"

/** IP 字面量（v4 / v6）不做 eTLD+1 折叠。 */
private val PROBE_HOST_IP_LITERAL = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

/**
 * 探针调度的并发组 key：源 URL 的 eTLD+1（最后两段）。
 * 同一 CDN 的子域（`a.cdn.x.com` / `b.cdn.x.com`）共享同一并发槽，
 * 避免并行探针在同一出口上堆积；IP 地址原样保留，解析失败归 [UNRESOLVED_PROBE_HOST]。
 */
fun probeHostOf(baseUrl: String): String {
    val host = runCatching { URI(baseUrl.trim()).host }
        .getOrNull()
        ?.trim()
        ?.trimEnd('.')
        ?.lowercase()
        .orEmpty()
    if (host.isEmpty()) return UNRESOLVED_PROBE_HOST
    if (host.contains(':') || PROBE_HOST_IP_LITERAL.matches(host)) return host
    val labels = host.split('.').filter { it.isNotEmpty() }
    return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
}
