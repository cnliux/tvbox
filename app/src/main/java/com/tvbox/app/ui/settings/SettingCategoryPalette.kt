package com.tvbox.app.ui.settings

import androidx.compose.ui.graphics.Color

/**
 * F-07：设置页分类卡调色板（8 组渐变）的**唯一真源**。
 *
 * 背景：手机版 `SettingsMobileScreen` 与电视版 `SettingsTvSplitScreen` 各自
 * 抄了一份完全相同的 16 个硬编码色（8 个 gradientStart + 8 个 gradientEnd），
 * 改一处视觉就必须同步改两处，漏改即两端观感不一致。
 * 这里集中到一处，两端只按下标取色。
 *
 * 说明：分类卡的渐变是「内容分区识别」用的品牌色，不追求跟随 6 套主题变化，
 * 因此保留固定色值（与主题 accent 是两个维度）；但**必须只有一处定义**。
 */
internal object SettingCategoryPalette {

    /** 分类卡渐变对：start 为高亮端、end 为暗端。下标与分类枚举声明顺序一一对应。 */
    val gradients: List<Pair<Color, Color>> = listOf(
        Color(0xFF4DA3FF) to Color(0xFF1E3A5F), // 通用与外观
        Color(0xFF1ED760) to Color(0xFF0E4A23), // 播放
        Color(0xFF9C9CFF) to Color(0xFF2E2E5C), // 手势与控制
        Color(0xFFFF7EB6) to Color(0xFF5C1E3A), // 找片与 AI
        Color(0xFFFFA42B) to Color(0xFF5C3A0E), // 数据源与网络
        Color(0xFFFF7043) to Color(0xFF5C2410), // 直播
        Color(0xFFFF7171) to Color(0xFF5C1E25), // 历史与数据
        Color(0xFF55F0A1) to Color(0xFF14532D), // 关于
    )

    fun gradientStart(index: Int): Color = gradients[index % gradients.size].first

    fun gradientEnd(index: Int): Color = gradients[index % gradients.size].second
}