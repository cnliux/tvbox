package com.tvbox.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.PlaybackEvidence
import com.tvbox.app.domain.PlaybackEvidenceEvent
import com.tvbox.app.domain.PlaybackHealthBadge
import com.tvbox.app.domain.PlaybackHealthMetrics
import com.tvbox.app.ui.components.TvFocusableSurface
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放管家展示面板（纯 Compose 展示组件）。
 *
 * 所有数据由外部传入，不直接依赖 ViewModel / Repository；点击徽章只展开说明，
 * 不触发换线等任何播放副作用。颜色全部取自项目主题 token，不硬编码背景色。
 */
@Composable
fun PlaybackManagerPanel(
    healthSummaryText: String,
    metrics: PlaybackHealthMetrics,
    badge: PlaybackHealthBadge,
    reasonText: String,
    evidence: List<PlaybackEvidence>,
    modifier: Modifier = Modifier,
) {
    var showReason by remember { mutableStateOf(false) }
    var showEvidence by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TvDimens.CardRadius),
        color = TvColors.SurfaceRaised,
        border = BorderStroke(1.dp, TvColors.Border),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "播放管家",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.TextPrimary,
                )
                BadgeChip(badge = badge, onClick = { showReason = !showReason })
            }

            Text(
                text = healthSummaryText,
                style = MaterialTheme.typography.bodySmall,
                color = TvColors.TextSecondary,
            )

            MetricsGrid(metrics = metrics)

            if (showReason) {
                Text(
                    text = "为什么这样评：$reasonText",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvColors.Surface, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }

            EvidenceToggle(
                count = evidence.size,
                expanded = showEvidence,
                onClick = { showEvidence = !showEvidence },
            )

            if (showEvidence) {
                EvidenceTimeline(rows = evidence.take(6))
            }
        }
    }
}

/** 三态（四态展示）徽章：推荐=青、较慢=琥珀、近期失败=红、未采样=灰。 */
@Composable
private fun PlaybackHealthBadge.color(): Color = when (this) {
    PlaybackHealthBadge.Recommended -> TvColors.Success
    PlaybackHealthBadge.Slower -> TvColors.Warning
    PlaybackHealthBadge.RecentFailure -> TvColors.Danger
    PlaybackHealthBadge.NoSample -> TvColors.TextTertiary
}

@Composable
private fun BadgeChip(badge: PlaybackHealthBadge, onClick: () -> Unit) {
    val badgeColor = badge.color()
    TvFocusableSurface(
        shape = RoundedCornerShape(8.dp),
        onClick = onClick,
        focusedContainerColor = badgeColor.copy(alpha = 0.30f),
        idleContainerColor = badgeColor.copy(alpha = 0.16f),
        content = {
            Text(
                text = badge.label,
                color = badgeColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        },
    )
}

/** 四联指标：成功率 / 首帧 P50 / 卡顿率 / 最近成功，两列铺排避免窄屏挤压。 */
@Composable
private fun MetricsGrid(metrics: PlaybackHealthMetrics) {
    val cells = listOf(
        "成功率" to "${(metrics.successRate * 100).toInt()}%",
        "首帧 P50" to (metrics.p50LatencyMs?.let { "$it ms" } ?: "—"),
        "卡顿率" to "${(metrics.stutterRate * 100).toInt()}%",
        "最近成功" to metrics.lastSuccessAgoText,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(2).forEach { rowCells ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCells.forEach { (label, value) ->
                    MetricCell(label = label, value = value, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(TvColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TvColors.TextTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = TvColors.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun EvidenceToggle(count: Int, expanded: Boolean, onClick: () -> Unit) {
    TvFocusableSurface(
        shape = RoundedCornerShape(8.dp),
        onClick = onClick,
        focusedContainerColor = TvColors.AccentSoft,
        idleContainerColor = TvColors.Surface,
        content = {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (expanded) "收起证据" else "查看证据（最多 6 / 共 $count 条）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = TvColors.Accent,
                )
                Text(
                    text = if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = TvColors.Accent,
                )
            }
        },
    )
}

@Composable
private fun PlaybackEvidenceEvent.color(): Color = when (this) {
    PlaybackEvidenceEvent.PlaybackError -> TvColors.Danger
    PlaybackEvidenceEvent.BufferStall, PlaybackEvidenceEvent.QualityDowngraded -> TvColors.Warning
    PlaybackEvidenceEvent.AutoSwitch, PlaybackEvidenceEvent.ManualSwitch,
    PlaybackEvidenceEvent.SourceLocked, PlaybackEvidenceEvent.FirstFrame -> TvColors.Success
    else -> TvColors.TextTertiary
}

@Composable
private fun EvidenceTimeline(rows: List<PlaybackEvidence>) {
    if (rows.isEmpty()) {
        Text(
            text = "暂无证据记录",
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextTertiary,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .background(row.event.color(), CircleShape),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = formatEvidenceTime(row.atMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = TvColors.TextTertiary,
                        )
                        Text(
                            text = row.event.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.TextPrimary,
                        )
                        Text(
                            text = row.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = TvColors.TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.detail.isNotBlank()) {
                        Text(
                            text = row.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TvColors.TextSecondary,
                        )
                    }
                    if (row.actionResult.isNotBlank()) {
                        Text(
                            text = "结果：${row.actionResult}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvColors.Accent,
                        )
                    }
                }
            }
        }
    }
}

private fun formatEvidenceTime(atMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(atMs))
