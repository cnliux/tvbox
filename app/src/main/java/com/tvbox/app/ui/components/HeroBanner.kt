package com.tvbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 影院主题 Hero 区（第 94/120/161 轮）。
 * 16:9 横屏与手机竖屏共用组件，只切换宽高比与内边距 / 简介行数；
 * 主次操作按钮是固定顺序的可聚焦元素，遥控器进入时不会因布局变化抢焦点。
 */
@Composable
fun CinemaHeroBanner(
    title: String,
    rating: String,
    description: String,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    portrait: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ratio = if (portrait) 0.9f else 16f / 9f
    val contentPadding = if (portrait) 16.dp else 32.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0f, 0f, 0f, 0.05f), Color(0f, 0f, 0f, 0.88f)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
            )
            if (rating.isNotBlank()) {
                Text(
                    text = "评分 $rating",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(1f, 0.84f, 0.33f),
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(1f, 1f, 1f, 0.86f),
                maxLines = if (portrait) 2 else 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay) { Text("立即播放") }
                Button(onClick = onDetail) { Text("查看详情") }
            }
        }
    }
}
