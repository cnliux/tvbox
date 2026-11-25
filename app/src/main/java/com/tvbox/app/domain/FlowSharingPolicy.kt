package com.tvbox.app.domain

/**
 * T5-37：TV 场景的 StateFlow 共享参数与焦点记忆策略。
 *
 * 模板默认的 `WhileSubscribed(5000, 0)` 在 TV 上是错的：按 HOME 去看别的 App
 * 五分钟后回来，源列表与海报会被重建、焦点掉回第一行。这里给出 TV 专属的
 * 停止超时 / 无限回放过期，以及一个不依赖 Bundle 的按路由焦点存档
 * （调用方写到 DataStore，避免 LMK 后 savedInstanceState 丢失）。
 *
 * 纯 Kotlin 实现，不引用 Compose / Lifecycle，便于 JVM 单测；UI 层可按需 adopt。
 */
object FlowSharingPolicy {

    /** TV 上 HOME 键 ≠ 离开页面：15 分钟后才停流。 */
    const val TV_STOP_TIMEOUT_MS: Long = 15L * 60 * 1_000L

    /** 手机/平板沿用模板的 5 秒。 */
    const val MOBILE_STOP_TIMEOUT_MS: Long = 5L * 1_000L

    /** 回放过期设为 Long.MAX_VALUE（等价于 Duration.INFINITE），保证回前台仍有最后一个值。 */
    const val REPLAY_EXPIRE_MS: Long = Long.MAX_VALUE

    fun stopTimeoutMs(isTv: Boolean): Long = if (isTv) TV_STOP_TIMEOUT_MS else MOBILE_STOP_TIMEOUT_MS

    /** 焦点锚点：行 id + 行内索引 + 滚动偏移，足以还原「刚才看到哪」。 */
    data class FocusAnchor(val rowId: String, val index: Int, val scrollOffset: Int) {
        init {
            require(index >= 0) { "index must not be negative" }
            require(scrollOffset >= 0) { "scrollOffset must not be negative" }
        }
    }

    /** 按路由存档的焦点记忆；超出 [limit] 时淘汰最早写入的路由。 */
    class FocusMemory(private val limit: Int = 64) {
        private val anchors = LinkedHashMap<String, FocusAnchor>()

        init {
            require(limit > 0) { "limit must be positive" }
        }

        @Synchronized
        fun save(route: String, anchor: FocusAnchor) {
            if (route.isBlank()) return
            anchors.remove(route)
            anchors[route] = anchor
            while (anchors.size > limit) {
                val oldest = anchors.keys.firstOrNull() ?: break
                anchors.remove(oldest)
            }
        }

        @Synchronized
        fun load(route: String): FocusAnchor? = anchors[route]

        @Synchronized
        fun clear() = anchors.clear()

        @Synchronized
        fun size(): Int = anchors.size
    }
}
