package com.tvbox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.window.layout.WindowMetricsCalculator
import com.tvbox.app.domain.DeviceMode

/**
 * 基于 Window Size Class 的设备类型判定辅助。
 *
 * 当前应用使用 [DeviceMode] 二值开关（Television / Mobile）控制布局，
 * 本工具类提供按窗口宽度细化的布局建议，供未来引入响应式布局时参考。
 *
 * 返回值：
 * - `TvLayoutMode.Compact`（< 600dp）：手机竖屏
 * - `TvLayoutMode.Medium`（600~840dp）：平板双栏布局候选
 * - `TvLayoutMode.Expanded`（> 840dp）：电视/大屏横屏
 *
 * 使用示例（未来迁移至 WindowSizeClass 时）：
 * ```kotlin
 * val layoutMode = calculateTvLayoutMode()
 * when (layoutMode) {
 *     TvLayoutMode.Compact -> MobileLayout(...)
 *     TvLayoutMode.Medium -> TabletDualPaneLayout(...)
 *     TvLayoutMode.Expanded -> TvLayout(...)
 * }
 * ```
 */
@Composable
fun calculateTvLayoutMode(): TvLayoutMode {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    return when {
        screenWidthDp < 600 -> TvLayoutMode.Compact
        screenWidthDp < 840 -> TvLayoutMode.Medium
        else -> TvLayoutMode.Expanded
    }
}

enum class TvLayoutMode {
    /** 手机竖屏：单列瀑布流布局。 */
    Compact,
    /** 平板/折叠屏展开：双栏布局候选（左列表 + 右详情）。 */
    Medium,
    /** 电视/大屏横屏：网格海报 + 焦点导航布局。 */
    Expanded,
}

/**
 * 判断是否为平板尺寸设备（用于布局决策）。
 *
 * 注意：此函数依赖 WindowManager 服务，应在主线程调用。
 * 返回值仅为估算，实际布局应由 Compose Window Size Class 驱动。
 */
fun isTabletSize(context: android.content.Context): Boolean {
    return try {
        val metrics = WindowMetricsCalculator.getOrCreate()
            .computeCurrentWindowMetrics(context)
        val widthDp = (metrics.bounds.width() / context.resources.displayMetrics.density).toInt()
        widthDp >= 600
    } catch (_: Exception) {
        false
    }
}
