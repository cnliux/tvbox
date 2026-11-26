package com.tvbox.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.ThemeAccent
import com.tvbox.app.domain.TvFontScale

data class TvColorTokens(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val focusRing: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

private val DefaultTokens = TvColorTokens(
    background = Color(0xFF121212),
    surface = Color(0xFF181818),
    surfaceRaised = Color(0xFF252525),
    surfaceOverlay = Color(0xE6181818),
    border = Color(0x664D4D4D),
    textPrimary = Color.White,
    textSecondary = Color(0xFFB3B3B3),
    // 三级文字常用于 12sp 设置标签/说明，落在 surfaceRaised(#252525) 上需 ≥4.5:1；
    // 原 #888888 实测约 4.3:1，提亮到 #9A9A9A 后约 5.4:1，仍与二级文字保持层级差。
    textTertiary = Color(0xFF9A9A9A),
    accent = Color(0xFF1ED760),
    accentStrong = Color(0xFF1DB954),
    accentSoft = Color(0x331ED760),
    focusRing = Color.White,
    success = Color(0xFF1ED760),
    warning = Color(0xFFFFA42B),
    danger = Color(0xFFF3727F),
)

private val CinemaTokens = TvColorTokens(
    background = Color(0xFF080B0B),
    surface = Color(0xFF121716),
    surfaceRaised = Color(0xFF1A211E),
    surfaceOverlay = Color(0xCC0C1110),
    border = Color(0x3357635C),
    textPrimary = Color(0xFFF5F8F6),
    textSecondary = Color(0xFFA6B0AA),
    // 与默认主题对齐：原 #7D8982 在 surfaceRaised(#1A211E) 上约 4.1:1，提亮到 #8F9A94 约 5.5:1。
    textTertiary = Color(0xFF8F9A94),
    accent = Color(0xFF29E68C),
    accentStrong = Color(0xFF55F0A1),
    accentSoft = Color(0x2229E68C),
    focusRing = Color(0xFFF7FAF8),
    success = Color(0xFF55F0A1),
    warning = Color(0xFFFFC857),
    danger = Color(0xFFFF7171),
)

val LocalTvColors = staticCompositionLocalOf { DefaultTokens }

// 任务7 多主题强调色：每档为一组完整 TvColorTokens。
// 设计约束：
// - 自适应：深色底 + 亮 accent，textTertiary 在 surfaceRaised 上对比度 ≥4.5:1；
// - TV 聚焦：focusRing 取高对比亮色，遥控器导航下描边清晰可见；
// - 每档 accent / accentStrong / accentSoft / focusRing 成套，warning/danger 与主色区分开。

/** 琥珀金：深棕底 + 暖金 accent，夜间低亮度友好。 */
private val AmberTokens = TvColorTokens(
    background = Color(0xFF14100A),
    surface = Color(0xFF1C170F),
    surfaceRaised = Color(0xFF261F14),
    surfaceOverlay = Color(0xE61C170F),
    border = Color(0x66563A1E),
    textPrimary = Color(0xFFFFF8EE),
    textSecondary = Color(0xFFD8C7AE),
    textTertiary = Color(0xFFB0A08A),
    accent = Color(0xFFFFC24B),
    accentStrong = Color(0xFFFFB020),
    accentSoft = Color(0x33FFC24B),
    focusRing = Color(0xFFFFD980),
    success = Color(0xFF7DDC7D),
    warning = Color(0xFFFF9E45),
    danger = Color(0xFFFF6B6B),
)

/** 深海蓝：深蓝底 + 冷蓝 accent，科技感。 */
private val OceanTokens = TvColorTokens(
    background = Color(0xFF0A1220),
    surface = Color(0xFF101A2C),
    surfaceRaised = Color(0xFF16243C),
    surfaceOverlay = Color(0xE6101A2C),
    border = Color(0x66334F73),
    textPrimary = Color(0xFFF2F7FF),
    textSecondary = Color(0xFFAEC0D8),
    textTertiary = Color(0xFF8CA0BC),
    accent = Color(0xFF4DA8FF),
    accentStrong = Color(0xFF2E90F0),
    accentSoft = Color(0x334DA8FF),
    focusRing = Color(0xFFA5D4FF),
    success = Color(0xFF4FD48A),
    warning = Color(0xFFFFB454),
    danger = Color(0xFFFF6B7F),
)

/** 星夜紫：深紫底 + 柔紫 accent，区别度高。 */
private val VioletTokens = TvColorTokens(
    background = Color(0xFF120E1C),
    surface = Color(0xFF1A1528),
    surfaceRaised = Color(0xFF231C36),
    surfaceOverlay = Color(0xE61A1528),
    border = Color(0x6646356B),
    textPrimary = Color(0xFFF8F5FF),
    textSecondary = Color(0xFFC6BCE0),
    textTertiary = Color(0xFFA396C4),
    accent = Color(0xFFB57BFF),
    accentStrong = Color(0xFF9A5CE8),
    accentSoft = Color(0x33B57BFF),
    focusRing = Color(0xFFD6B8FF),
    success = Color(0xFF6FD69A),
    warning = Color(0xFFFFB454),
    danger = Color(0xFFFF6B8F),
)

/** 绯红：深酒红底 + 暖红 accent，活力。 */
private val CrimsonTokens = TvColorTokens(
    background = Color(0xFF170D10),
    surface = Color(0xFF20131A),
    surfaceRaised = Color(0xFF2B1A22),
    surfaceOverlay = Color(0xE620131A),
    border = Color(0x665E3640),
    textPrimary = Color(0xFFFFF5F7),
    textSecondary = Color(0xFFE0BFC8),
    textTertiary = Color(0xFFBC98A3),
    accent = Color(0xFFFF6B8B),
    accentStrong = Color(0xFFF04868),
    accentSoft = Color(0x33FF6B8B),
    focusRing = Color(0xFFFFB3C2),
    success = Color(0xFF7DDC7D),
    warning = Color(0xFFFFB454),
    danger = Color(0xFFFF5252),
)

data class TvLayoutTokens(
    val posterGridMinWidth: Dp,
    val historyGridMinWidth: Dp,
    val episodeGridMinWidth: Dp,
    val settingsGridMinWidth: Dp,
    val platformLiveColumns: Int,
)

private val NormalLayoutTokens = TvLayoutTokens(
    posterGridMinWidth = 148.dp,
    historyGridMinWidth = 230.dp,
    episodeGridMinWidth = 132.dp,
    settingsGridMinWidth = 176.dp,
    platformLiveColumns = 5,
)

private val LargeLayoutTokens = TvLayoutTokens(
    posterGridMinWidth = 178.dp,
    historyGridMinWidth = 276.dp,
    episodeGridMinWidth = 156.dp,
    settingsGridMinWidth = 210.dp,
    platformLiveColumns = 4,
)

private val ExtraLargeLayoutTokens = TvLayoutTokens(
    posterGridMinWidth = 216.dp,
    historyGridMinWidth = 322.dp,
    episodeGridMinWidth = 184.dp,
    settingsGridMinWidth = 250.dp,
    platformLiveColumns = 3,
)

val LocalTvLayout = staticCompositionLocalOf { NormalLayoutTokens }

object TvColors {
    val Background: Color @Composable get() = LocalTvColors.current.background
    val Surface: Color @Composable get() = LocalTvColors.current.surface
    val SurfaceRaised: Color @Composable get() = LocalTvColors.current.surfaceRaised
    val SurfaceOverlay: Color @Composable get() = LocalTvColors.current.surfaceOverlay
    val Border: Color @Composable get() = LocalTvColors.current.border
    val TextPrimary: Color @Composable get() = LocalTvColors.current.textPrimary
    val TextSecondary: Color @Composable get() = LocalTvColors.current.textSecondary
    val TextTertiary: Color @Composable get() = LocalTvColors.current.textTertiary
    val Accent: Color @Composable get() = LocalTvColors.current.accent
    val AccentStrong: Color @Composable get() = LocalTvColors.current.accentStrong
    val AccentSoft: Color @Composable get() = LocalTvColors.current.accentSoft
    val FocusRing: Color @Composable get() = LocalTvColors.current.focusRing
    val Success: Color @Composable get() = LocalTvColors.current.success
    val Warning: Color @Composable get() = LocalTvColors.current.warning
    val Danger: Color @Composable get() = LocalTvColors.current.danger
}

object TvLayout {
    val PosterGridMinWidth: Dp @Composable get() = LocalTvLayout.current.posterGridMinWidth
    val HistoryGridMinWidth: Dp @Composable get() = LocalTvLayout.current.historyGridMinWidth
    val EpisodeGridMinWidth: Dp @Composable get() = LocalTvLayout.current.episodeGridMinWidth
    val SettingsGridMinWidth: Dp @Composable get() = LocalTvLayout.current.settingsGridMinWidth
    val PlatformLiveColumns: Int @Composable get() = LocalTvLayout.current.platformLiveColumns
}

object TvDimens {
    val RailWidth = 76.dp
    val PageHorizontalPadding = 28.dp
    val PageVerticalPadding = 24.dp
    val SectionGap = 16.dp
    val CardRadius = 12.dp

    // 设置页公共尺寸（SettingsBits 与手机/TV 两个设置页共用）。
    val SettingsIconBoxSize = 38.dp
    val SettingsRowIconSize = 20.dp
    val SettingsGroupDividerIndent = 64.dp
    val SettingsRowVerticalPadding = 14.dp
    val SettingsRowInnerSpacing = 10.dp
    val SettingsRowIconGap = 12.dp
}

private fun colorScheme(tokens: TvColorTokens): ColorScheme = darkColorScheme(
    primary = tokens.accent,
    primaryContainer = tokens.accentStrong,
    secondary = tokens.accentStrong,
    tertiary = tokens.warning,
    background = tokens.background,
    surface = tokens.surface,
    surfaceVariant = tokens.surfaceRaised,
    onPrimary = Color(0xFF06130A),
    onPrimaryContainer = Color(0xFF06130A),
    onSecondary = Color(0xFF07182E),
    onTertiary = Color(0xFF241300),
    onBackground = tokens.textPrimary,
    onSurface = tokens.textPrimary,
    onSurfaceVariant = tokens.textSecondary,
    outline = tokens.border,
    error = tokens.danger,
)

private val TvTypography = Typography(
    // 每个样式必须显式指定 lineHeight：Typography() 的具名参数是整体替换而非合并默认值，
    // 只写 fontSize 会让 lineHeight 保持 Unspecified——下游一旦做 lineHeight.toDp() 等换算
    // 就会抛 IllegalStateException（历史上一次启动崩溃的根因）。行高取保守的 ~1.15-1.25 倍字号。
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

private fun Typography.scaled(multiplier: Float): Typography = copy(
    displayLarge = displayLarge.scaled(multiplier),
    displayMedium = displayMedium.scaled(multiplier),
    displaySmall = displaySmall.scaled(multiplier),
    headlineLarge = headlineLarge.scaled(multiplier),
    headlineMedium = headlineMedium.scaled(multiplier),
    headlineSmall = headlineSmall.scaled(multiplier),
    titleLarge = titleLarge.scaled(multiplier),
    titleMedium = titleMedium.scaled(multiplier),
    titleSmall = titleSmall.scaled(multiplier),
    bodyLarge = bodyLarge.scaled(multiplier),
    bodyMedium = bodyMedium.scaled(multiplier),
    bodySmall = bodySmall.scaled(multiplier),
    labelLarge = labelLarge.scaled(multiplier),
    labelMedium = labelMedium.scaled(multiplier),
    labelSmall = labelSmall.scaled(multiplier),
)

internal fun TextStyle.scaled(multiplier: Float): TextStyle = copy(
    fontSize = fontSize.scaled(multiplier),
    letterSpacing = letterSpacing.scaled(multiplier),
    lineHeight = lineHeight.scaled(multiplier),
)

private fun TextUnit.scaled(multiplier: Float): TextUnit =
    if (isSpecified) this * multiplier else this

private fun layoutTokens(fontScale: TvFontScale): TvLayoutTokens = when (fontScale) {
    TvFontScale.Normal -> NormalLayoutTokens
    TvFontScale.Large -> LargeLayoutTokens
    TvFontScale.ExtraLarge -> ExtraLargeLayoutTokens
}

/** 按用户选择的主题强调色映射到一组颜色 token；默认回退到默认绿。 */
private fun tokensFor(themeAccent: ThemeAccent): TvColorTokens = when (themeAccent) {
    ThemeAccent.Default -> DefaultTokens
    ThemeAccent.Cinema -> CinemaTokens
    ThemeAccent.Amber -> AmberTokens
    ThemeAccent.Ocean -> OceanTokens
    ThemeAccent.Violet -> VioletTokens
    ThemeAccent.Crimson -> CrimsonTokens
}

@Composable
fun TVBoxTheme(
    fontScale: TvFontScale = TvFontScale.Normal,
    themeAccent: ThemeAccent = ThemeAccent.Default,
    content: @Composable () -> Unit,
) {
    val tokens = tokensFor(themeAccent)
    val typography = TvTypography.scaled(fontScale.typographyScale)
    val context = androidx.compose.ui.platform.LocalContext.current
    // 系统「减少动效」关闭时，焦点缩放与过渡一并关闭，但保留描边保证遥控器可见。
    val reduceMotion = androidx.compose.runtime.remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val focusTokens = focusTokensFor(themeAccent, ringColor = tokens.focusRing, reduceMotion = reduceMotion)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTvColors provides tokens,
        LocalTvLayout provides layoutTokens(fontScale),
        LocalTvFocus provides focusTokens,
    ) {
        MaterialTheme(
            colorScheme = colorScheme(tokens),
            typography = typography,
            content = content,
        )
    }
}
