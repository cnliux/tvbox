package com.tvbox.app.crash

import com.tvbox.app.util.TLog
import android.os.Handler
import android.os.Looper
import com.tvbox.app.domain.GlobalPlayState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量 ANR 看门狗：周期性向主线程 post 心跳，若超过阈值未得到响应，
 * 抓取主线程与关键线程栈，经 [CrashHandler] 写入统一的崩溃日志目录。
 *
 * 设计取舍：
 * - 只在主线程**真的卡住**时才抓栈（先设 pending=true 再 post，主线程执行时清零）；
 *   心跳正常时不产生任何文件，避免把正常调度延迟误报为 ANR。
 * - 用独立后台线程做检测，检测线程不参与应用业务，自己也卡住时不影响主线程。
 * - 抓栈后将 pending 置 false 并等待下一次周期，避免一次卡顿刷屏写满日志。
 * - 抓栈异常一律吞掉：诊断设施绝不能反过来造成崩溃。
 */
object AnrWatchdog {
    private const val TAG = "TVBoxAnrWatchdog"
    private const val CHECK_INTERVAL_MS = 2_000L
    /** 连续 5 个周期（10 秒）无响应才抓栈，短时 GC/布局抖动不误报。 */
    private const val ANR_THRESHOLD_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    private var lastSeenAtMs = System.currentTimeMillis()
    private var watchdogThread: Thread? = null
    private val started = AtomicBoolean(false)

    private val heartbeat = Runnable {
        lastSeenAtMs = System.currentTimeMillis()
        pending.set(false)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        lastSeenAtMs = System.currentTimeMillis()
        watchdogThread = Thread({
            while (started.get()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    val silentMs = System.currentTimeMillis() - lastSeenAtMs
                    if (silentMs < ANR_THRESHOLD_MS) continue
                    // pending 阻止一次卡顿重复抓栈：只有上一次已被主线程确认或已抓过才再置位。
                    if (!pending.compareAndSet(false, true)) continue
                    mainHandler.post(heartbeat)
                    // 再等一个周期，给主线程最后一次响应机会。
                    Thread.sleep(CHECK_INTERVAL_MS)
                    if (!pending.get()) continue
                    // T3-23：ANR检测时记录面包屑，便于复现排查
                    GlobalPlayState.breadcrumb.record(
                        System.currentTimeMillis(),
                        "anr_detected",
                        "silent_ms=${silentMs}"
                    )
                    val stack = captureStacks()
                    CrashHandler.writeCrashLog(
                        Thread.currentThread(),
                        IllegalStateException("ANR 主线程无响应 ${silentMs}ms\n$stack"),
                    )
                    TLog.w(TAG) { "检测到 ANR（主线程静默 ${silentMs}ms），已写入诊断日志" }
                    pending.set(false)
                    lastSeenAtMs = System.currentTimeMillis()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (t: Throwable) {
                    TLog.w(TAG) { "ANR 看门狗自身异常（已忽略）：${t.message}" }
                }
            }
        }, "tvbox-anr-watchdog").apply {
            isDaemon = true
            start()
        }
        // 启动后立即投一次心跳，建立首次基准。
        mainHandler.post(heartbeat)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { mainHandler.removeCallbacks(heartbeat) }
        runCatching { watchdogThread?.interrupt() }
        watchdogThread = null
    }

    /** 抓取主线程与当前看门狗线程的栈；主线程可能卡在 native，取不到时降级为空串。 */
    private fun captureStacks(): String = buildString {
        append("--- main (${Looper.getMainLooper().thread.name}) ---\n")
        append(runCatching { Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "\tat $it" } }
            .getOrDefault("<unavailable>"))
        append("\n--- watchdog ---\n")
        append(Thread.currentThread().stackTrace.joinToString("\n") { "\tat $it" })
    }
}
