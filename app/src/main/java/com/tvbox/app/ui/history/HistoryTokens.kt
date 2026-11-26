package com.tvbox.app.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    object M {
        // 手机历史行：海报封面尺寸（64×86 → 80×112 → 96×136 → 112×160 → 128×184 → 现版 96×136dp）。
        // 海报严格 2:3 电影封面比例（96×136≈0.706），卡片整体更紧凑，右侧三项数据
        // （标题 / 上次看到 / 进度条）不再因海报过高而下方留白。
        // 行高与海报同高，保证全出血不露背景。
        val RowH    = 136.dp
        val PW      = 96.dp
        val PH      = 136.dp
        val PosterPadStart = 0.dp
        val PosterPadEnd   = 0.dp
        val Radius  = 6.dp
        // M-07：字号不再写死，改为从 MaterialTheme.typography 派生，
        // 用户选「超大」时跟着放大（此前固定 16/13/11sp 导致手机端字号档对它无效）。
        // 仍是 @Composable getter，调用方保持在 @Composable 作用域内即可，无需改调用点。
        val TitleSz: TextUnit @Composable get() = MaterialTheme.typography.titleMedium.fontSize
        val MetaSz: TextUnit @Composable get() = MaterialTheme.typography.bodyMedium.fontSize
        val BadgeSz: TextUnit @Composable get() = MaterialTheme.typography.labelSmall.fontSize
        val SegH    = 0.5.dp
        val ProgH   = 4.dp
        val PadH    = 16.dp
        val PadV    = 12.dp
        val Gap     = 12.dp
        val ScrimH  = 36.dp
        val DotSize = 9.dp
        /** 小圆点角标黑描边宽度（基准稿 .cover-badge.dot border 2px）。 */
        val DotBorder = 2.dp
        val DelBtn  = 48.dp
        /** 封面角标贴边距：对齐基准稿 top 4px / right 4px。 */
        val BadgeEdge = 4.dp
        /** 角标 pill 基准高度：随海报缩到 18dp，避免 11sp 文字上下溢出被裁。 */
        val BadgeH   = 18.dp
        val BadgeMinW = 18.dp
        val BadgePadH = 5.dp
        /** 角标 pill 差异化圆角（右上圆角 + 左下圆角，书签形）。 */
        val BadgeCornerTR = 4.dp
        val BadgeCornerBL = 7.dp
        /** 底部通栏标签高度：随海报缩到 22dp，容下"更新至 NN 集"不裁切。 */
        val TagH    = 22.dp
        val PremiereDot = 4.dp
        /** 琥珀 "有更新" chip 描边 accent 亮度（不随 theme 变）。 */
        val AmberChipBorder = DirtyAmber.copy(alpha = 0.7f)
    }
}
