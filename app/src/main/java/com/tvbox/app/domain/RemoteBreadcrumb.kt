package com.tvbox.app.domain

/**
 * T3-23：播放/遥控会话的 64 帧面包屑。
 *
 * 「按了⏪没反应」没有堆栈：异常出口（换源 / 失败 / ANR / 菜单连按 5 次）
 * 落盘环形缓冲，就能把「复现不出」变成「贴出来就能改」。
 * 只存枚举 tag + 短 detail，不存片名 / 源 URL（对齐 T3-19 编译期禁 PII）。
 */
data class BreadcrumbFrame(val atMs: Long, val tag: String, val detail: String)

class RemoteBreadcrumb(private val capacity: Int = 64) {

    private val frames = ArrayDeque<BreadcrumbFrame>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun record(atMs: Long, tag: String, detail: String = "") {
        val safeTag = tag.take(16).replace(Regex("[\\r\\n·]"), " ")
        val safeDetail = detail.take(48).replace(Regex("[\\r\\n·]"), " ")
        frames.addLast(BreadcrumbFrame(atMs, safeTag, safeDetail))
        while (frames.size > capacity) frames.removeFirst()
    }

    /** 最新在前，便于「t-0.0s」在第一行。 */
    @Synchronized
    fun snapshot(): List<BreadcrumbFrame> = frames.toList().asReversed()

    /** 相对最后一帧的时间偏移，供 OSD 展示 `t-1.1s`。 */
    @Synchronized
    fun render(): String {
        val all = snapshot()
        if (all.isEmpty()) return "（无面包屑）"
        val newest = all.first().atMs
        return all.joinToString("\n") { frame ->
            val delta = (newest - frame.atMs).coerceAtLeast(0L)
            val label = when {
                delta >= 1_000L -> "t-${delta / 1_000L}.${(delta % 1_000L) / 100}s"
                delta >= 100L -> "t-0.${delta / 100L}s"
                else -> "t-${delta}ms"
            }
            buildString {
                append(label).append(' ').append(frame.tag)
                if (frame.detail.isNotEmpty()) append(' ').append(frame.detail)
            }
        }
    }

    /** 异常出口：取走快照并清空，避免同一次故障重复落盘。 */
    @Synchronized
    fun drainForFailure(): List<BreadcrumbFrame> {
        val copy = snapshot()
        frames.clear()
        return copy
    }

    @Synchronized
    fun clear() = frames.clear()

    @Synchronized
    fun size(): Int = frames.size
}
