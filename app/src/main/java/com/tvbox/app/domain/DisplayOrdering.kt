package com.tvbox.app.domain

import java.text.Collator
import java.util.Locale

/**
 * T5-35：大小写 / 排序 / RTL 三个 locale 语义的单一出口。
 *
 * `title.uppercase()` 在土耳其语里把 i 变成 İ（索引栏点了没反应）；
 * `sortedBy { it.name }` 用默认 Locale 让「一」排到「中」后面（国内就炸）；
 * RTL 下 DPAD_LEFT 的「前进」语义必须翻转，否则阿拉伯语用户觉得遥控是反的。
 */
object DisplayOrdering {

    /** 中文按拼音（Collator PRIMARY），同音再按 Unicode 码点稳定裁决。 */
    fun chineseComparator(): Comparator<String> {
        val collator = Collator.getInstance(Locale.CHINESE).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        return Comparator { a, b ->
            collator.compare(a, b).let { if (it != 0) it else a.compareTo(b) }
        }
    }

    /** 索引键用 ROOT，避免 tr_TR 的 i→İ 让英文索引栏失效。 */
    fun indexKey(title: String): String =
        title.trim().uppercase(Locale.ROOT).firstOrNull()?.toString().orEmpty()

    /** 展示用当前 Locale，与索引键分离。 */
    fun displayUpper(title: String): String = title.uppercase(Locale.getDefault())

    /** RTL 布局下 LEFT=前进；其他布局 RIGHT=前进。路由层一处改，全 App 受益。 */
    enum class Direction { Left, Right }

    fun forwardDirection(isRtl: Boolean): Direction = if (isRtl) Direction.Left else Direction.Right
}
