package com.tvbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.DeviceMode

/**
 * 扫码/远程配置三态徽标（B3）：设置页所有"是否已配置"类状态共用。
 * - NotConfigured：未配置（也没有内置兜底）
 * - BuiltIn：未由用户覆盖，当前生效的是 APK 内置配置
 * - UserOverridden：用户已扫码/填写，当前生效用户值
 */
enum class ScanConfigState(val label: String, val color: Color, val hint: String) {
    NotConfigured("未配置", Color(0xFF8A8A8A), "尚未配置"),
    BuiltIn("内置", Color(0xFF4DA3FF), "未填写时使用 APK 内置配置"),
    UserOverridden("用户配置", Color(0xFF37D67A), "已由用户配置，优先于内置值"),
    ;

    companion object {
        /** 依据"是否已填写用户值"映射三态；isBuiltInAvailable=false 的配置项没有内置兜底。 */
        fun of(userConfigured: Boolean, isBuiltInAvailable: Boolean = true): ScanConfigState = when {
            userConfigured -> UserOverridden
            isBuiltInAvailable -> BuiltIn
            else -> NotConfigured
        }
    }
}

/** 配置状态徽标：圆角小标签，用于"视频接口 / 直播源 / AI Key / 设备模式"等状态展示。 */
@Composable
fun ScanConfigBadge(
    scanState: ScanConfigState,
    modifier: Modifier = Modifier,
) {
    Text(
        text = scanState.label,
        modifier = modifier
            .background(scanState.color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = scanState.color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 设备模式摘要：偏好值 + Auto 时的识别结果，例如"自动 · 识别为电视"。 */
fun DeviceMode.summaryLabel(detected: DeviceMode?): String = when (this) {
    DeviceMode.Auto -> "自动 · 识别为${detected?.displayName ?: "…"}"
    else -> displayName
}

@Composable
private fun StatusChip(label: String, value: String, valueColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(Color(0xFF161616), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF8F8F8F),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 设置页状态摘要（B4）：当前视频源 / 直播源 / AI 模型 / 设备模式。
 * TV 端单行排布；手机端窄屏自动拆行（每行最多 2 个）。
 */
@Composable
fun SettingsStatusSummary(
    items: List<Pair<String, String>>,
    valueColor: Color = Color(0xFF55F0A1),
    twoColumns: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (twoColumns) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
            items.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (label, value) ->
                        StatusChip(label, value, valueColor)
                    }
                }
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            items.forEach { (label, value) ->
                StatusChip(label, value, valueColor)
            }
        }
    }
}
