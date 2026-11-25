package com.tvbox.app.domain

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * T7-46：三时钟各司其职，避免老盒子 RTC 没电导致「1970 年」把进度/冷却/分组全搞坏。
 *
 * - [mono] 只用于**间隔 / 超时 / 冷却 / 退避**：单调时钟，用户改系统表也不受影响；
 * - [wall] 只用于**展示 / 跨天分组**：有 NTP 偏移时用校准值，否则用「开机基准 + elapsedRealtime」兜底；
 * - [logical] 只用于**同进程内定序**：治「同一毫秒两次写入」的归档键冲突。
 */
object Clocks {
    private val logicalCounter = AtomicLong(0)
    private val ntpDelta = AtomicLong(0L)
    @Volatile
    private var ntpSynced = false

    /** 1970 / 2000 以前的墙钟视作不可信（老盒子 RTC 没电的典型值）。 */
    const val MIN_TRUSTED_WALL_MS = 1_000_000_000_000L // 2001-09-09

    fun setNtpOffset(deltaMs: Long, synced: Boolean = true) {
        ntpDelta.set(deltaMs)
        ntpSynced = synced
    }

    fun clearNtpOffset() {
        ntpDelta.set(0L)
        ntpSynced = false
    }

    fun isWallTrusted(systemWallMs: Long = System.currentTimeMillis()): Boolean =
        systemWallMs >= MIN_TRUSTED_WALL_MS

    /**
     * @param bootWallMs 启动时记录的墙钟基准；0 表示未知，此时只能退回系统墙钟。
     * 默认参数在 JVM 单测里会触发 android.jar stub，测试请显式传值。
     */
    fun wall(
        systemWallMs: Long = System.currentTimeMillis(),
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        bootWallMs: Long = 0L,
    ): Long = if (ntpSynced) {
        systemWallMs + ntpDelta.get()
    } else if (bootWallMs > 0L) {
        bootWallMs + elapsedRealtimeMs
    } else {
        systemWallMs
    }

    /** 单调毫秒；间隔计算一律走这里。测试请显式传值。 */
    fun mono(elapsedRealtimeMs: Long = SystemClock.elapsedRealtime()): Long = elapsedRealtimeMs

    /** 进程内逻辑序号，单调递增。 */
    fun logical(): Long = logicalCounter.incrementAndGet()

    /** 冲突裁决键：墙钟与逻辑序号取大者，保证同毫秒写入也有稳定先后。 */
    fun orderKey(wallMs: Long, logicalSeq: Long): Long = maxOf(wallMs, logicalSeq)
}
