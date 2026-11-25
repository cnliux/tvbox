package com.tvbox.app.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * S5-31：电源类资源（MulticastLock / WakeLock）的**总闸**。
 *
 * 即便每个 acquire 点都写了 try/finally，仍可能有漏网路径（进程被杀、某处漏 release）。
 * 本 Registry 在 `TvBoxApplication.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` 与
 * Activity onDestroy 时统一 `releaseAll()`，保证盒子熄屏待机电流能降下来。
 * 只持有弱引用语义的句柄：release 后自动从集合移除，不泄漏锁对象。
 */
object PowerResourceRegistry {
    fun interface Releasable {
        fun releaseOnce()
    }

    private val active = Collections.newSetFromMap(ConcurrentHashMap<Releasable, Boolean>())

    fun register(resource: Releasable): Releasable = resource.also { active.add(it) }

    fun unregister(resource: Releasable) {
        active.remove(resource)
    }

    /** 兜底释放全部在册资源；每个句柄至多 release 一次，异常不影响其它资源。 */
    fun releaseAll(): Int {
        var released = 0
        active.toList().forEach { resource ->
            runCatching {
                resource.releaseOnce()
                released++
            }
            active.remove(resource)
        }
        return released
    }

    fun activeCount(): Int = active.size

    /** 仅供单测隔离用例状态。 */
    internal fun clearForTest() = active.clear()
}
