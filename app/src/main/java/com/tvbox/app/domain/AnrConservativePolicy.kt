package com.tvbox.app.domain

/**
 * S5-29：ANR 保守降级模式的纯函数策略。
 *
 * 背景：AnrWatchdog 检测到主线程无响应后会把 [com.tvbox.app.crash.AnrWatchdog.isConservativeMode]
 * 置 true。本对象集中定义「降级后各资源档位怎么调」，保证 ViewModel/UI 各处消费口径一致，
 * 且判定本身无 Android 依赖、可直接在 JVM 单测中断言。
 *
 * 降级档位：
 * - 弹幕：ANR 时弹幕渲染本身可能就是卡顿源之一，直接关闭；
 * - 搜索并发：6 → 3，降低协程与网络争抢；
 * - LiveSourceHealthCheck 探针超时：8s → 3s，死链更快放弃、少占线程；
 * - UI：顶部显示琥珀色横幅提示用户当前处于降级模式。
 */
object AnrConservativePolicy {
    /** 降级时弹幕关闭：只有非降级模式才允许弹幕。 */
    fun danmakuEnabled(conservative: Boolean): Boolean = !conservative

    /** 搜索/多源并发：正常 6，降级 3。 */
    fun searchParallelism(conservative: Boolean): Int = if (conservative) 3 else 6

    /** 直播源健康探针单源超时（毫秒）：正常 8s，降级 3s。 */
    fun probeTimeoutMs(conservative: Boolean): Long = if (conservative) 3_000L else 8_000L

    /** 降级时 UI 顶部显示琥珀横幅。 */
    fun showAmberBanner(conservative: Boolean): Boolean = conservative
}
