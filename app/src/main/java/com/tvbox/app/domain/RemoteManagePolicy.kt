package com.tvbox.app.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * S1-01：局域网远程管理面的安全策略（纯领域，便于单测）。
 *
 * 默认**只听回环**：服务只在用户主动打开对话框时启动，且除非显式 opt-in，
 * 手机无法从同网段访问。Lan 开关默认 false，与 Go 侧平台解析服务的语义对齐。
 */
object RemoteManagePolicy {
    const val PREFERRED_PORT = 9978
    const val LOOPBACK_HOST = "127.0.0.1"
    const val LAN_HOST = "0.0.0.0"

    /** 连续失败 5 次 → 该 IP 退避 30 秒；期间返回 429，不再做 token 比较。 */
    const val MAX_FAILED_ATTEMPTS = 5
    const val LOCKOUT_WINDOW_MS = 30_000L

    fun bindHost(lanEnabled: Boolean): String = if (lanEnabled) LAN_HOST else LOOPBACK_HOST

    /** 展示给用户的地址：未开 LAN 时必须是回环，避免二维码指向同网段不可达/暴露的地址。 */
    fun displayHost(lanEnabled: Boolean, detectedLanAddress: String): String =
        if (lanEnabled && detectedLanAddress.isNotBlank()) detectedLanAddress else LOOPBACK_HOST
}

/**
 * IP → 连续失败计数 / 退避截止时间的线程安全限流器。
 * 每次失败都刷新窗口；[isLocked] 只读不产生副作用；[recordSuccess] 清掉该 IP 的计数。
 */
class RemoteLoginThrottle(
    private val maxAttempts: Int = RemoteManagePolicy.MAX_FAILED_ATTEMPTS,
    private val lockoutMs: Long = RemoteManagePolicy.LOCKOUT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class State(val failures: Int, val lastFailureMs: Long, val untilMs: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun isLocked(ip: String, nowMs: Long = clock()): Boolean =
        (states[ip]?.untilMs ?: 0L) > nowMs

    /** 记一次失败；返回是否因此进入退避（第 5 次起为 true）。 */
    fun recordFailure(ip: String, nowMs: Long = clock()): Boolean {
        val previous = states[ip]
        // 连续失败窗口内累加；隔太久则从头计数（退避中的状态天然属于窗口内）。
        val failures = if (previous != null && nowMs - previous.lastFailureMs <= lockoutMs) {
            previous.failures + 1
        } else {
            1
        }
        val locked = failures >= maxAttempts
        states[ip] = State(failures, nowMs, if (locked) nowMs + lockoutMs else 0L)
        return locked
    }

    fun recordSuccess(ip: String) {
        states.remove(ip)
    }

    fun clear() = states.clear()
}
