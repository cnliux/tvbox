package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 安全并发集合工具：避免 TVBox 中多处网络/解析回调写入同一 Set/List 时的并发问题。
 *
 * 主要用途：
 *   1. 搜索三源并行增量去重
 *   2. LiveLineFallback 的 failedSourceIndexes 记录
 *   3. PlaybackHealth 的 entry 更新
 */
object ConcurrentCollections {

    /** 线程安全的 MutableSet，适合少量写入、多次读的场景。 */
    fun <T : Any> safeSet(initial: Set<T> = emptySet()): MutableSet<T> =
        ConcurrentHashMap.newKeySet<T>().also { initial.forEach { t -> it.add(t) } }

    /**
     * 真正带锁的 MutableList。注意迭代时仍需按 JDK 约定在同一实例上同步：
     * `synchronized(list) { list.forEach { ... } }`。
     */
    fun <T> synchronizedList(initial: List<T> = emptyList()): MutableList<T> =
        Collections.synchronizedList(ArrayList(initial))

    /** 线程安全的 Map，适合缓存与统计。 */
    fun <K, V> concurrentMap(): ConcurrentHashMap<K, V> = ConcurrentHashMap()
}

/**
 * 指数退避重试工具。
 *
 * @param maxRetries 最大重试次数（不含首次尝试）；总共最多尝试 [maxRetries] + 1 次
 * @param baseDelayMs 基础延迟毫秒
 * @param maxDelayMs 最大延迟上限
 * @param onRetry 每次重试前的回调（可记录日志）
 */
suspend fun <T> exponentialRetry(
    maxRetries: Int = 3,
    baseDelayMs: Long = 500L,
    maxDelayMs: Long = 10_000L,
    onRetry: (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    require(maxRetries >= 0) { "maxRetries 不能为负数" }
    var lastError: Throwable? = null
    repeat(maxRetries + 1) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            // 结构化并发：协程取消不是可重试错误，必须原样抛出，不能写进失败缓存或继续退避。
            throw e
        } catch (e: Throwable) {
            lastError = e
            if (attempt == maxRetries) return@repeat
            val delayMs = (baseDelayMs * (1L shl attempt.coerceAtMost(30))).coerceAtMost(maxDelayMs)
            onRetry(attempt + 1, delayMs, e)
            delay(delayMs)
        }
    }
    throw lastError ?: IllegalStateException("exponentialRetry completed without success")
}
