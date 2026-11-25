package com.tvbox.app.domain

import kotlin.random.Random

/**
 * 平台直播重连退避策略（任务2/3 自适应修复）。
 *
 * 把原本内嵌在 ViewModel 里的 `shl` 指数退避抽成可测试、可按失败原因差异化的纯策略：
 * - 按失败原因给不同基线：正常关流（Ended）恢复最快、卡顿（Stall）稍慢、硬错误（Error）立即；
 * - 指数递增（2^n）+ 上限封顶，避免重连间隔无界拉长；
 * - 加抖动（jitter），避免多台设备在 CDN 故障时形成同步重试风暴。
 *
 * 纯函数、不持有时钟，便于单元测试覆盖每个原因的退避曲线。
 */
class RecoveryBackoff(
    /** 各失败原因的退避基线（第 1 次重试的延迟）。 */
    private val baseMs: Map<LiveLineFallbackReason, Long> = DEFAULT_BASE_MS,
    /** 退避上限，防止间隔无界增长。 */
    private val maxMs: Long = DEFAULT_MAX_MS,
    /** 抖动比例（0..1）：实际延迟 = 指数值 ± jitterRatio/2，平滑多设备同步重试。 */
    private val jitterRatio: Double = DEFAULT_JITTER_RATIO,
    private val random: Random = Random.Default,
) {
    /**
     * 计算第 [retry] 次（1-based）重连的延迟毫秒。
     *
     * - [retry] <= 1 返回该原因基线；
     * - 之后按 2^(retry-1) 指数增长，封顶 [maxMs]；
     * - 最后叠加抖动，保证非负且不超过 [maxMs]。
     */
    fun nextDelayMs(reason: LiveLineFallbackReason, retry: Int): Long {
        val base = baseMs[reason] ?: DEFAULT_BASE_MS.getValue(LiveLineFallbackReason.Ended)
        if (retry <= 1) return applyJitter(base)
        // retry=2 → base*2, retry=3 → base*4 ... 用移位避免浮点误差，再封顶。
        val exp = retry - 1
        val shifted = if (exp >= Long.SIZE_BITS - 1) Long.MAX_VALUE else base shl exp
        val capped = if (shifted < 0) maxMs else shifted.coerceAtMost(maxMs)
        return applyJitter(capped)
    }

    /** 在目标值附近叠加 ±jitterRatio/2 的随机偏移，结果夹在 0..[maxMs]。 */
    private fun applyJitter(target: Long): Long {
        if (jitterRatio <= 0.0) return target.coerceIn(0L, maxMs)
        val span = target * jitterRatio
        val offset = (random.nextDouble() - 0.5) * 2.0 * span
        val jittered = target + offset.toLong()
        return jittered.coerceIn(0L, maxMs)
    }

    companion object {
        /** 默认各原因基线：正常关流 1.5s、卡顿 3s、硬错误 0（立即换线）。 */
        val DEFAULT_BASE_MS: Map<LiveLineFallbackReason, Long> = mapOf(
            LiveLineFallbackReason.Ended to 1_500L,
            LiveLineFallbackReason.Stall to 3_000L,
            LiveLineFallbackReason.Error to 0L,
        )

        const val DEFAULT_MAX_MS = 8_000L
        const val DEFAULT_JITTER_RATIO = 0.3
    }
}
