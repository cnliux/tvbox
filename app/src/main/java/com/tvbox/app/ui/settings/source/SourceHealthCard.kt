package com.tvbox.app.ui.settings.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.DiagnosticEnvelope
import com.tvbox.app.domain.DiagnosticSection
import com.tvbox.app.domain.PlaybackHealthSnapshot
import com.tvbox.app.domain.toJson
import com.tvbox.app.ui.theme.TvColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** T2-18：源健康「体检卡」的遥控停留/触摸长按进入时长（毫秒）。 */
const val SOURCE_HEALTH_CARD_HOLD_MS: Long = 1_000L

/** T2-18：可用度统计只取近 7 天内有活动的健康记录。 */
private const val AVAILABILITY_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000L
/**
 * T2-18：体检卡的纯展示模型。字段全 val，标 [Immutable]（S5-26 约定），
 * 卡片组合只读这份快照，避免重组时重新扫描健康表。
 *
 * - [availabilityPercent]：近 7 天播放成功/总次数百分比；-1 表示窗口内无记录（进度条显示空态）。
 * - [fastestFirstFrameMs]：该源所有线路成功首帧延迟的最小值（毫秒）；null 表示没有首帧样本。
 * - [lastFailureAtMs]：该源最近一次播放失败时间戳；0 表示无失败记录。
 * - [inCooldown]：最近失败是否仍在 30 分钟冷却窗口内（播放时会跳过该源的慢/失败线路）。
 * - [tuningMarks]：用户调校标记（置顶/屏蔽/UA 自定义），空表示该源未被手动调校。
 * - [pinned]：置顶位预留——当前设置页尚未暴露独立置顶 API（domain 层 [ManagedSource]
 *   的 pinned 字段已预留），接入后由调用方置 true，卡片自动多出「置顶」标记。
 *
 * 口径说明：播放健康记录按 `影片|集次|线路` 维度累计（保留 30 天），
 * 按「最近 7 天内有活动的记录」过滤后再做成功/失败占比，是现有数据下最贴近
 * 「近 7 天可用度」的口径；不是逐天打点。
 */
@Immutable
data class SourceHealthCardModel(
    val sourceName: String,
    val fingerprint: String,
    val availabilityPercent: Int,
    val totalAttempts: Int,
    val fastestFirstFrameMs: Long?,
    val lastFailureAtMs: Long,
    val inCooldown: Boolean,
    val probeLatencyMs: Long?,
    val blocked: Boolean,
    val customUa: Boolean,
    val pinned: Boolean,
) {
    val tuningMarks: List<String>
        get() = buildList {
            if (pinned) add("置顶")
            if (blocked) add("屏蔽")
            if (customUa) add("UA自定义")
        }

    /** 体检卡内「可用度」一行的人类可读文案。 */
    val availabilityLabel: String
        get() = if (availabilityPercent < 0) "近 7 天暂无播放记录"
        else "近 7 天可用度 $availabilityPercent%（${totalAttempts} 次尝试）"

    /** 体检卡内「最快线路」一行的人类可读文案。 */
    val fastestLineLabel: String
        get() = when {
            fastestFirstFrameMs != null -> "首帧 ${formatLatency(fastestFirstFrameMs)}"
            probeLatencyMs != null -> "探活 ${formatLatency(probeLatencyMs)}"
            else -> "暂无样本"
        }

    /** 体检卡内「最近失败」一行的人类可读文案。 */
    val lastFailureLabel: String
        get() = if (lastFailureAtMs <= 0L) "暂无失败记录"
        else {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(lastFailureAtMs))
            "最近失败 $time" + if (inCooldown) " · 冷却中" else " · 冷却已解除"
        }

    internal companion object {
        fun formatLatency(ms: Long): String =
            if (ms < 1000) "${ms}ms" else "${ms / 1000}.${(ms % 1000) / 100}s"
    }
}

/**
 * 从页面侧可拿到的真实数据构建体检卡模型（纯函数，便于单测）。
 *
 * 健康表键格式见 [com.tvbox.app.domain.playbackHealthKey]：`影片|集次|线路`，
 * 线路段 = lineId（为空时回退 lineName/name），所以按两段匹配：line.id 优先，
 * 同时兼容以名称落键的旧数据。
 *
 * 「UA 自定义」判定：源地址行内带 `|User-Agent=` 参数（全局自定义 UA 对所有源
 * 生效，不构成单源调校，不计入标记）。
 */
fun buildSourceHealthCardModel(
    line: ApiLine,
    fingerprint: String,
    health: PlaybackHealthSnapshot,
    latencies: Map<String, Long?>,
    settings: AppSettings,
    pinned: Boolean = false,
    nowMs: Long = System.currentTimeMillis(),
): SourceHealthCardModel {
    val sourceId = line.id.ifBlank { line.name }
    val sourceEntries = health.entries.values.filter { entry ->
        val keySource = entry.key.split('|').lastOrNull()
        keySource == line.id || keySource == sourceId
    }
    // 近 7 天窗口：只统计最近活动落在窗口内的记录。
    val windowMs = nowMs - AVAILABILITY_WINDOW_MS
    val recentEntries = sourceEntries.filter { it.latestActivityAtMs >= windowMs }
    val attempts = recentEntries.sumOf { it.attemptCount }
    val successes = recentEntries.sumOf { it.successCount }
    val availabilityPercent = if (attempts <= 0) -1 else ((successes * 100f / attempts)).toInt().coerceIn(0, 100)
    val fastestFirstFrameMs = sourceEntries
        .flatMap { it.successLatencyMs }
        .filter { it > 0L }
        .minOrNull()
    val lastFailureAtMs = sourceEntries.maxOfOrNull { it.lastFailureAtMs }?.takeIf { it > 0L } ?: 0L
    val inCooldown = sourceEntries.any { it.isRecentlyUnhealthy(nowMs) }
    val customUa = line.baseUrls.any { it.contains("user-agent", ignoreCase = true) }
    return SourceHealthCardModel(
        sourceName = line.name,
        fingerprint = fingerprint,
        availabilityPercent = availabilityPercent,
        totalAttempts = attempts,
        fastestFirstFrameMs = fastestFirstFrameMs,
        lastFailureAtMs = lastFailureAtMs,
        inCooldown = inCooldown,
        probeLatencyMs = latencies[line.id],
        blocked = line.id in settings.blockedSourceIds,
        customUa = customUa,
        pinned = pinned,
    )
}

/**
 * T2-18：体检报告导出。复用 [DiagnosticEnvelope] 统一信封（协议 v1）：
 * 先 [DiagnosticEnvelope.redacted] 整体脱敏再落盘，保证报告可分享。
 */
fun buildSourceHealthReport(
    model: SourceHealthCardModel,
    appVersion: String,
    versionCode: Int,
    deviceModel: String,
    androidVersion: String,
    sdkInt: Int,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val lines = listOf(
        "源：${model.sourceName}（指纹 ${model.fingerprint.ifBlank { "未知" }}）",
        "可用度：${model.availabilityLabel}",
        "最快线路：${model.fastestLineLabel}",
        "最近失败：${model.lastFailureLabel}",
        "用户调校：" + (model.tuningMarks.ifEmpty { listOf("无") }.joinToString("、")),
    )
    val envelope = DiagnosticEnvelope(
        generatedAtMs = nowMs,
        appVersion = appVersion,
        appVersionCode = versionCode,
        deviceModel = deviceModel,
        androidVersion = androidVersion,
        sdkInt = sdkInt,
        sections = mapOf(
            "source_health" to DiagnosticSection(
                title = "源健康体检报告",
                lines = lines,
            ),
        ),
    )
    return envelope.redacted().toJson()
}

/**
 * T2-18：源健康「体检卡」浮层。
 *
 * 进入方式（在 [com.tvbox.app.ui.SourceManageCard] 上）：
 * - 触摸设备：长按（pointerInput 检测）；
 * - 遥控器：焦点停留在源行满 1s（onFocusChanged + LaunchedEffect）。
 *
 * 两个操作按钮：[立即体检] 触发一轮（后台）源体检；[导出报告] 生成
 * [DiagnosticEnvelope] 报告文本（脱敏）供下载。
 */
@Composable
fun SourceHealthCardDialog(
    model: SourceHealthCardModel,
    healthCheckRunning: Boolean,
    onRunHealthCheck: () -> Unit,
    onExportReport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(role = Role.Button, onClick = onDismiss),
        shape = cardShape,
        color = TvColors.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题行：源名 + 8 字符指纹徽章 + 关闭。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = model.sourceName,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val badgeShape = RoundedCornerShape(6.dp)
                Surface(
                    shape = badgeShape,
                    color = TvColors.Accent.copy(alpha = 0.12f),
                    contentColor = TvColors.Accent,
                ) {
                    Text(
                        text = "▪${model.fingerprint.ifBlank { "—" }}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                SourceHealthCardButton(
                    text = "关闭",
                    enabled = !healthCheckRunning,
                    onClick = onDismiss,
                )
            }
            // 可用度进度条（近 7 天）。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HealthStatRow(
                    label = "可用度",
                    value = model.availabilityLabel,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(TvColors.AccentSoft.copy(alpha = 0.4f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (model.availabilityPercent < 0) 0.001f else model.availabilityPercent / 100f)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(TvColors.Accent),
                    )
                }
            }
            // 指标行。
            HealthStatRow(label = "最快线路", value = model.fastestLineLabel)
            HealthStatRow(label = "最近失败", value = model.lastFailureLabel, valueColor =
                if (model.lastFailureAtMs > 0L && model.inCooldown) TvColors.Danger else TvColors.TextPrimary)
            // 用户调校标记。
            HealthStatRow(
                label = "用户调校",
                value = if (model.tuningMarks.isEmpty()) "无" else model.tuningMarks.joinToString(" · "),
                valueColor = if (model.tuningMarks.isEmpty()) TvColors.TextSecondary else TvColors.Accent,
            )
            // 操作按钮：立即体检 / 导出报告。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SourceHealthCardButton(
                    text = if (healthCheckRunning) "体检中…" else "立即体检",
                    enabled = !healthCheckRunning,
                    onClick = onRunHealthCheck,
                    modifier = Modifier.weight(1f),
                )
                SourceHealthCardButton(
                    text = "导出报告",
                    enabled = !healthCheckRunning,
                    onClick = onExportReport,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 体检卡内的单行「标签：数值」。 */
@Composable
private fun HealthStatRow(
    label: String,
    value: String,
    valueColor: Color = TvColors.TextPrimary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TvColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 体检卡按钮：与设置页 TvFocusButton 同规格（48dp 最小目标 + 焦点描边），自包含避免跨文件私有依赖。 */
@Composable
private fun SourceHealthCardButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) TvColors.Surface else TvColors.SurfaceRaised.copy(alpha = 0.5f))
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "体检卡：$text" }
            .focusable(enabled)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) TvColors.TextPrimary else TvColors.TextTertiary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 体检卡的居中浮层包装：半透明遮罩 + 居中卡片。放在调用方（源管理页）组合树里，
 * 与现有 AlertDialog 同级呈现；遮罩点击关闭由卡片自身 [SourceHealthCardDialog] 的点击承担。
 */
@Composable
fun SourceHealthCardOverlay(
    model: SourceHealthCardModel,
    healthCheckRunning: Boolean,
    onRunHealthCheck: () -> Unit,
    onExportReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        SourceHealthCardDialog(
            model = model,
            healthCheckRunning = healthCheckRunning,
            onRunHealthCheck = onRunHealthCheck,
            onExportReport = onExportReport,
            onDismiss = onDismiss,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
    }
}

/**
 * 从调用方缓存构建体检卡模型：同一数据不重建，避免重组时重复扫描健康表。
 */
@Composable
fun rememberSourceHealthCardModel(
    line: ApiLine,
    fingerprint: String,
    health: PlaybackHealthSnapshot,
    latencies: Map<String, Long?>,
    settings: AppSettings,
): SourceHealthCardModel = remember(line, fingerprint, health, latencies, settings) {
    buildSourceHealthCardModel(line, fingerprint, health, latencies, settings)
}
