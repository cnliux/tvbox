package com.tvbox.app.domain

/**
 * 手机播放控件与手势策略（第 96/97/122/123/163/164 轮）。
 * 纯几何与状态判定，不依赖 Android；横竖屏、安全区与手势阈值由 UI 传入。
 */

enum class GestureZone { Left, Right, Center }

enum class GestureIntent { None, Seek, Brightness, Volume }

data class GestureSample(
    val zone: GestureZone,
    val deltaX: Float,
    val deltaY: Float,
    val durationMs: Long,
)

/**
 * 冲突消解规则：横滑进度优先于亮度/音量；位移不足或主轴不明显时判为误触。
 * 左半屏上下滑亮度、右半屏上下滑音量、中央区不接管纵向滑动。
 */
fun resolveGesture(
    sample: GestureSample,
    minSwipePx: Float = 24f,
    horizontalDominance: Float = 1.2f,
): GestureIntent {
    val dx = kotlin.math.abs(sample.deltaX)
    val dy = kotlin.math.abs(sample.deltaY)
    if (dx < minSwipePx && dy < minSwipePx) return GestureIntent.None
    if (dx >= minSwipePx && dx >= dy * horizontalDominance) return GestureIntent.Seek
    if (dy < minSwipePx) return GestureIntent.None
    return when (sample.zone) {
        GestureZone.Left -> GestureIntent.Brightness
        GestureZone.Right -> GestureIntent.Volume
        GestureZone.Center -> GestureIntent.None
    }
}

data class SafeAreaInsets(
    val leftPx: Int = 0,
    val topPx: Int = 0,
    val rightPx: Int = 0,
    val bottomPx: Int = 0,
)

/** 基础内边距叠加安全区，避免刘海 / 手势条把控件挤出或重叠。 */
fun horizontalContentPadding(insets: SafeAreaInsets, basePaddingPx: Int = 16): Pair<Int, Int> =
    (basePaddingPx + insets.leftPx.coerceAtLeast(0)) to (basePaddingPx + insets.rightPx.coerceAtLeast(0))

fun bottomContentPadding(insets: SafeAreaInsets, basePaddingPx: Int = 16): Int =
    basePaddingPx + insets.bottomPx.coerceAtLeast(0)

/** 大屏单手可达区：控件上边缘落在屏幕下方 [1 - reachRatio] 区域内视为可达。 */
fun isInSingleHandReach(controlTopPx: Int, screenHeightPx: Int, reachRatio: Float = 0.75f): Boolean =
    screenHeightPx > 0 && controlTopPx >= screenHeightPx * reachRatio.coerceIn(0.1f, 0.95f)

enum class PlayerControlGroup { TopBar, CenterPlayback, BottomProgress, BottomActions, OverlayPanels }

/** 控件拆分：高频操作（播放/暂停、进度）固定在底部可达区，弹层独立成组。 */
val PLAYER_CONTROL_LAYOUT: Map<PlayerControlGroup, Boolean> = mapOf(
    PlayerControlGroup.TopBar to false,
    PlayerControlGroup.CenterPlayback to false,
    PlayerControlGroup.BottomProgress to true,
    PlayerControlGroup.BottomActions to true,
    PlayerControlGroup.OverlayPanels to false,
)

enum class CastState { Disconnected, Searching, Connected }

fun castStateLabel(state: CastState): String = when (state) {
    CastState.Disconnected -> "未连接"
    CastState.Searching -> "搜索设备中"
    CastState.Connected -> "已连接"
}
