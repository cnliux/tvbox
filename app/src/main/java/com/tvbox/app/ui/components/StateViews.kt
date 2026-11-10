package com.tvbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 统一的加载 / 错误 / 空态三件套（第 95/121/162 轮）。
 * 首页、搜索、历史、源管理共用；按钮自带焦点态，TV 遥控器可直接操作。
 */

private val PlaceholderColor = Color(1f, 1f, 1f, 0.18f)

@Composable
fun SkeletonGrid(
    rows: Int = 2,
    columns: Int = 5,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows.coerceIn(1, 6)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(columns.coerceIn(1, 8)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(140.dp)
                            .background(PlaceholderColor, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

/**
 * 可重试错误与引导空态直接委托给全站既有的 `ErrorState` / `EmptyState`：
 * 两套视觉不再并行维护，TV 焦点态、动效与字号缩放都沿用既有实现。
 * 骨架屏（`SkeletonGrid`）是本批新增能力，保持自绘。
 */
@Composable
fun RetryableError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ErrorState(message = message, onRetry = onRetry, modifier = modifier)
}

@Composable
fun GuidedEmptyState(
    title: String,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = title,
        subtitle = hint,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}
