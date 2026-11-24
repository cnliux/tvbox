package com.tvbox.app.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.ui.theme.LocalTvColors
import com.tvbox.app.ui.theme.TvColors

/**
 * 手机版历史页的设计令牌。
 *
 * 所有颜色、尺寸常量集中于此，组件侧不得出现魔法值；更换视觉风格只需改本文件。
 */
object HistoryTokens {
    val Canvas      = Color(0xFF121212)
    val Divider     = Color(0xFF222222)
    val Title       = Color(0xFFEEEEEE)
    val Meta        = Color(0xFF888888)
    val MetaDim     = Color(0xFF5C5C5C)
    val IconIdle    = Color(0xFF555555)

    /** "有更新"主色：红→橙渐变起点/终点 + 角标点 / 文字区。 */
    val NewFrom     = Color(0xFFFF416C)
    val NewTo       = Color(0xFFFF4B2B)
    val NewDot      = Color(0xFFFF3B52)
    val NewText     = Color(0xFFFF5C7A)
    val NewTextBg   = Color(0xFF3A1620)
    val NewTextLine = Color(0xFF5C2130)

    /** 直播态专用。 */
    val LiveGreen   = Color(0xFF00FF88)
    val LiveBg      = Color(0xFF1E3B2E)
    val CaughtUpBg  = Color(0xFF3C3C3C)

    /** 进度条默认渐变；≥99.9% 时切换为已完成灰紫。 */
    val ProgFrom    = Color(0xFF00F2FE)
    val ProgTo      = Color(0xFFB224EF)
    val ProgDoneA   = Color(0xFF6B6B7B)
    val ProgDoneB   = Color(0xFF4A4A5C)

    /** 海报顶部 34dp 遮罩，保障浅色封面下文字可读。 */
    val ScrimTop    = Color(0x73000000)

    /** "已修改/有更新"统一琥珀，与设置页同源，用于左竖条和琥珀 Chip。 */
    val DirtyAmber  = Color(0xFFFFB020)

    /** 搜索框 focus 光环 accent（复用主题 accent）。 */
    @Composable
    fun SearchFocusAccent(): Color = TvColors.Accent.copy(alpha = 0.5f)

    /** 影院模式下 accent 降饱和；一处算，别散着写。 */
    @Composable
    fun accent(alpha: Float = 1f): Color =
        if (LocalTvColors.current.accent == Color(0xFF29E68C)) NewFrom.copy(alpha = alpha * .82f)
        else NewFrom.copy(alpha = alpha)

    object M {
        // 严格对齐 history_card_refactor_demo.html：行高 86dp，海报 64×86dp 全出血（无内边距）。
        // 旧版海报 box 64×86 但内部 padding(8/4) 吃掉 12dp，可视仅 52×86，与基准稿不符；
        // 现去内边距让封面真正 64×86，实现「收紧 + 放大封面」。
        val RowH    = 86.dp
        val PW      = 64.dp
        val PH      = 86.dp
        val PosterPadStart = 0.dp
        val PosterPadEnd   = 0.dp
        val Radius  = 6.dp
        val TitleSz = 16.sp
        val MetaSz  = 12.sp
        val BadgeSz = 10.sp
        val SegH    = 0.5.dp
        val ProgH   = 3.dp
        val PadH    = 16.dp
        val PadV    = 12.dp
        val Gap     = 12.dp
        val ScrimH  = 34.dp
        val DotSize = 8.dp
        /** 小圆点角标黑描边宽度（基准稿 .cover-badge.dot border 2px）。 */
        val DotBorder = 2.dp
        val DelBtn  = 48.dp
        /** 封面角标贴边距：对齐基准稿 top 4px / right 4px。 */
        val BadgeEdge = 4.dp
        /** 角标 pill 基准高度（基准稿 .cover-badge：min-width 16 / height 16）。 */
        val BadgeH   = 16.dp
        val BadgeMinW = 16.dp
        val BadgePadH = 4.dp
        /** 角标 pill 差异化圆角（右上圆角 + 左下圆角，书签形）。 */
        val BadgeCornerTR = 4.dp
        val BadgeCornerBL = 8.dp
        /** 底部标签高度；"今日开播"呼吸白点直径。 */
        val TagH    = 20.dp
        val PremiereDot = 4.dp
        /** 琥珀 "有更新" chip 描边 accent 亮度（不随 theme 变）。 */
        val AmberChipBorder = DirtyAmber.copy(alpha = 0.7f)
    }
}
