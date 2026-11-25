package com.tvbox.app.domain

/**
 * T3-24：本机健康页的四/五个数 + ANR/jank 采集阈值。
 *
 * ANR 看门狗（crash/AnrWatchdog.kt）与 jankRatio（StartupMetrics.kt）已有采集源，
 * 这里补「连按菜单 5 次进入健康页」的判定与一屏可读的汇总格式。
 */
object HealthPageMetrics {

    const val MENU_PRESSES_TO_OPEN: Int = 5
    const val LONG_FRAME_MS: Long = 90L

    fun shouldOpen(menuPresses: Int): Boolean = menuPresses >= MENU_PRESSES_TO_OPEN

    /** 最近秩（nearest-rank）P95；空样本返回 0，不伪造「很快」的假象。 */
    fun p95(samples: List<Long>): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val rank = (sorted.size * 95 + 99) / 100
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }

    fun format(snapshot: HealthSnapshot): String = buildString {
        append("本次开机\n")
        append("首帧P95 ${snapshot.ttfpP95Ms}ms\n")
        append("长帧 ${snapshot.longFrames}\n")
        append("被限流 ${snapshot.throttled} 次\n")
        append("内存档 ${snapshot.tier}\n")
        append("已打码导出 ${if (snapshot.redactedExport) "✓" else "✗"}\n")
    }
}
