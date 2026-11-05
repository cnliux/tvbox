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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.LiveChannel

/** EPG 窄卡固定展示的节目条数。 */
private const val LIVE_EPG_CARD_ROWS = 4

/** EPG 窄卡的一行：时间 + 节目名 + 进度。 */
internal data class LiveEpgRow(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
)

/**
 * 取 EPG 卡要展示的 4 条节目：从正在播出的那条起向后取 4 条；
 * 当前时刻不在任何节目区间时，取第一条尚未结束的节目向后 4 条；
 * 全部已播完则退回最后 4 条，避免卡片空白。
 */
internal fun buildLiveEpgDisplay(
    programs: List<EpgProgram>,
    nowMs: Long = System.currentTimeMillis(),
): List<LiveEpgRow> {
    if (programs.isEmpty()) return emptyList()
    val startIndex = programs.indexOfFirst { nowMs < it.stopMs }
        .takeIf { it >= 0 }
        ?: (programs.size - LIVE_EPG_CARD_ROWS).coerceAtLeast(0)
    return programs.drop(startIndex)
        .take(LIVE_EPG_CARD_ROWS)
        .map { LiveEpgRow(title = it.title, startMs = it.startMs, stopMs = it.stopMs) }
}

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
 */
@Composable
internal fun LiveEpgCard(
    channel: LiveChannel,
    lineIndex: Int,
    nowProgram: EpgProgram?,
    programs: List<LiveEpgRow>,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6141418))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveChannelLogo(
                logo = channel.logo,
                name = channel.name,
                size = 32.dp,
                initialFontSize = 11.sp,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 13.sp,
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
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = " · 线路${lineIndex + 1}/${channel.lines.size}",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 9.sp,
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
            Box(
                modifier = Modifier.fillMaxWidth().height(84.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无节目单", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
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
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = row.title,
                            color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        // 28dp 进度条：仅当前节目按实际播出进度填充。
                        Box(
                            modifier = Modifier
                                .width(28.dp)
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
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${channel.groupName} · ${channel.number}/$channelCount · 线路 ${lineIndex + 1}/$lineCount",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
