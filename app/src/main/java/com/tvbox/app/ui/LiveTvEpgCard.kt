package com.tvbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.LiveChannel

/** EPG 窄卡固定展示的节目条数。 */
private const val LIVE_EPG_CARD_ROWS = 4

/**
 * 取样式可用于布局换算的行高：优先显式 lineHeight，未指定时按字号推导 ~1.25 倍。
 * 与 [com.tvbox.app.ui.components] 的媒体卡同口径——保证结果是合法 Sp，
 * 避免字号缩放到「超大」档时占位高度仍写死、节目名被挤压截断。
 */
private fun TextStyle.effectiveLineHeight(): TextUnit = when {
    lineHeight.isSpecified -> lineHeight
    fontSize.isSpecified -> fontSize * 1.25f
    else -> TextUnit(16f, TextUnitType.Sp)
}

/** EPG 窄卡的一行：时间 + 节目名 + 进度。 */
internal data class LiveEpgRow(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
)

/**
 * 取 EPG 卡要展示的节目：从正在播出的那条起向后取 [rows] 条。
 * 当前时刻不在任何节目区间时按 [outOfRangePolicy]（设置页「节目单越界处理」）处理：
 * - `clip`（默认）：取第一条尚未结束的节目向后 [rows] 条；全部已播完退回最后 [rows] 条，避免卡片空白；
 * - `next`：只显示下一档起的节目；没有下一档（全部播完）则留空；
 * - `blank`：越界直接留空（卡片显示「暂无节目单」）。
 */
internal fun buildLiveEpgDisplay(
    programs: List<EpgProgram>,
    nowMs: Long = System.currentTimeMillis(),
    rows: Int = LIVE_EPG_CARD_ROWS,
    outOfRangePolicy: String = "clip",
): List<LiveEpgRow> {
    if (programs.isEmpty()) return emptyList()
    val rowCount = rows.coerceAtLeast(1)
    val inRange = programs.any { it.startMs <= nowMs && nowMs < it.stopMs }
    if (!inRange) {
        when (outOfRangePolicy.trim().lowercase()) {
            "blank" -> return emptyList()
            "next" -> {
                val nextIndex = programs.indexOfFirst { it.startMs > nowMs }
                if (nextIndex < 0) return emptyList()
                return programs.drop(nextIndex).take(rowCount).mapToLiveEpgRows()
            }
        }
    }
    val startIndex = programs.indexOfFirst { nowMs < it.stopMs }
        .takeIf { it >= 0 }
        ?: (programs.size - rowCount).coerceAtLeast(0)
    return programs.drop(startIndex).take(rowCount).mapToLiveEpgRows()
}

private fun List<EpgProgram>.mapToLiveEpgRows(): List<LiveEpgRow> =
    map { LiveEpgRow(title = it.title, startMs = it.startMs, stopMs = it.stopMs) }

/** 单行进度（0f..1f）：只在正在播出的节目上有意义。 */
private fun LiveEpgRow.progress(nowMs: Long): Float {
    val span = stopMs - startMs
    if (span <= 0L) return 0f
    return ((nowMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
}

/**
 * 右下角 EPG 窄卡（280dp，约 140dp 高）：
 * 顶部 32dp 台标 + 频道名 + 「●直播中/线路」，下方最多 4 条节目行（时间 12sp 等宽 +
 * 节目名 10sp 单行截断 + 28dp 进度条）。无节目单时显示「暂无节目单」。
 *
 * S3-19：[stale] 为 true 时节目单已过期（缓存超 7 天，后台静默重拉中），
 * 卡片右上角叠加「~」角标提示用户看到的是旧数据；角标是叠加层，不占布局。
 */
@Composable
internal fun LiveEpgCard(
    channel: LiveChannel,
    lineIndex: Int,
    nowProgram: EpgProgram?,
    programs: List<LiveEpgRow>,
    stale: Boolean = false,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(485.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xE6141418))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 台标长方形：90×64dp（由 web/preview_live_tv_ui_drag.html 调参生成）
                LiveChannelLogo(
                    logo = channel.logo,
                    name = channel.name,
                    width = 90.dp,
                    height = 64.dp,
                    initialFontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp).background(LiveTvAccent, CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "直播中",
                            color = LiveTvAccent,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        Text(
                            text = " · 线路${lineIndex + 1}/${channel.lines.size}",
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            Spacer(Modifier.height(4.dp))
            if (programs.isEmpty()) {
                // 占位高度按 labelSmall 行高 × 固定节目条数换算，随字号设置缩放，
                // 避免超大字号下写死的 84.dp 撑不住文字（与媒体卡行高换算同口径）。
                val density = LocalDensity.current
                val placeholderHeight = with(density) {
                    (MaterialTheme.typography.labelSmall.effectiveLineHeight() * LIVE_EPG_CARD_ROWS).toDp()
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(placeholderHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("暂无节目单", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    programs.forEach { row ->
                        // 当前节目按 startMs 判定：EPG 里同一条目时间唯一。
                        val isCurrent = nowProgram != null && row.startMs == nowProgram.startMs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isCurrent) LiveTvEpgCurrentBg else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatClock(row.startMs),
                                color = if (isCurrent) LiveTvAccent else Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = row.title,
                                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            // 48dp 进度条：仅当前节目按实际播出进度填充（web 调参）。
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(2.dp)
                                    .clip(CircleShape)
                                    .background(LiveTvTrackBg),
                            ) {
                                if (isCurrent) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(row.progress(nowMs))
                                            .background(LiveTvAccent),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // S3-19：过期角标 `~` 叠加在卡片右上角，不占布局、不挤压既有行。
        if (stale) {
            Text(
                text = "~",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 10.dp),
            )
        }
    }
}

/** 换台时左下角频道 badge，与顶/底信息条同步淡出。 */
@Composable
internal fun LiveChannelBadge(
    channel: LiveChannel,
    lineIndex: Int,
    lineCount: Int,
    channelCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = channel.name,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${channel.groupName} · ${channel.number}/$channelCount · 线路 ${lineIndex + 1}/$lineCount",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
