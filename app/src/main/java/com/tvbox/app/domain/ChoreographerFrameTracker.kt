package com.tvbox.app.domain

import android.os.Build
import android.view.Choreographer
import java.util.ArrayDeque

/**
 * T3-24：基于 Choreographer 的 Compose jank 采样器。
 *
 * 每帧回调时，依据 [LONG_FRAME_NS] 判定是否为长帧（卡顿）。
 * 外部按窗口长度（默认 60 秒）读取并清零计数，用于健康页指标汇总。
 */
class ChoreographerFrameTracker(
    private val choreographer: Choreographer = Choreographer.getInstance(),
    /** 长帧阈值（纳秒），默认 90ms。 */
    val longFrameNs: Long = HealthPageMetrics.LONG_FRAME_MS * 1_000_000L,
) {

    private var lastFrameTimeNs: Long = 0L
    private var currentWindowStartMs: Long = 0L
    private var windowLongFrames: Int = 0
    private var windowTotalFrames: Int = 0
    private val samples = ArrayDeque<Pair<Long, Int>>() // (windowStartMs, longFrames)

    /** 当前未汇出的长帧数（供 Compose 根节点按需查询）。 */
    var pendingLongFrames: Int = 0
        internal set

    fun start() {
        if (lastFrameTimeNs > 0L) return // 已启动
        lastFrameTimeNs = System.nanoTime()
        currentWindowStartMs = System.currentTimeMillis()
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        runCatching { choreographer.removeFrameCallback(frameCallback) }
        lastFrameTimeNs = 0L
    }

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val nowNs = System.nanoTime()
            val deltaNs = nowNs - lastFrameTimeNs
            lastFrameTimeNs = nowNs

            windowTotalFrames++
            if (deltaNs >= longFrameNs) {
                windowLongFrames++
                pendingLongFrames++
            }

            // 每 60s 汇出一次窗口数据
            val nowMs = System.currentTimeMillis()
            if (nowMs - currentWindowStartMs >= WINDOW_SCAN_MS) {
                val snapshot = windowLongFrames
                samples.addLast(currentWindowStartMs to snapshot)
                while (samples.size > MAX_WINDOWS) samples.removeFirst()
                windowLongFrames = 0
                windowTotalFrames = 0
                currentWindowStartMs = nowMs
            }
            choreographer.postFrameCallback(this)
        }
    }

    /**
     * 取最近 [windowCount] 个窗口的长帧总数，并清空累计（pendingLongFrames 归零）。
     * 若为首次调用，返回所有历史窗口之和。
     */
    fun collectAndReset(windowCount: Int = SAMPLE_WINDOWS): Int {
        // 先计入尚未汇出的当前窗口
        val partial = if (windowTotalFrames > 0) windowLongFrames else 0
        pendingLongFrames = 0
        val total = partial + samples.toList().asReversed().take(windowCount).sumOf { it.second }
        samples.clear()
        return total.coerceAtLeast(0)
    }

    companion object {
        private const val WINDOW_SCAN_MS = 60_000L
        private const val MAX_WINDOWS = 60 // 保留最多 60 分钟窗口
        private const val SAMPLE_WINDOWS = 10
    }
}
