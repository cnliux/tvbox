package com.tvbox.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.data.DanmakuMessage
import kotlinx.coroutines.flow.Flow

private data class DanmakuEntry(
    val id: Long,
    val message: DanmakuMessage,
    val lane: Int,
)

/**
 * 直播弹幕叠加层：从右向左滚动，按轨道分行，避免同一条线上的弹幕互相重叠。
 *
 * 每条弹幕用 [Animatable] 驱动位移，位移只在 [graphicsLayer] 中读取，
 * 因此每帧只更新绘制层、不触发重组，电视盒子上也不会掉帧。
 */
@Composable
fun DanmakuOverlay(
    messages: Flow<DanmakuMessage>,
    modifier: Modifier = Modifier,
    laneCount: Int = 5,
    laneHeight: androidx.compose.ui.unit.Dp = 30.dp,
    fontSize: TextUnit = 16.sp,
) {
    val entries = remember { mutableStateListOf<DanmakuEntry>() }
    var sequence by remember { mutableLongStateOf(0L) }

    LaunchedEffect(messages) {
        messages.collect { message ->
            sequence += 1
            val entry = DanmakuEntry(
                id = sequence,
                message = message,
                lane = (sequence % laneCount).toInt(),
            )
            entries.add(entry)
            // 同屏条数上限：超量时淘汰最早的一条，避免长时间播放后无限堆积。
            // 每条弹幕都是独立的 Composable + Animatable，低端 TV 盒上同屏 60 条
            // 会明显拖慢绘制；24 条已覆盖 5 条轨道且不会挤满画面。
            while (entries.size > MAX_CONCURRENT) {
                entries.removeAt(0)
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val trackWidth = constraints.maxWidth.toFloat()
        entries.forEach { entry ->
            key(entry.id) {
                DanmakuTrack(
                    entry = entry,
                    trackWidth = trackWidth,
                    laneHeightDp = laneHeight,
                    fontSize = fontSize,
                    onFinished = { entries.remove(entry) },
                )
            }
        }
    }
}

@Composable
private fun DanmakuTrack(
    entry: DanmakuEntry,
    trackWidth: Float,
    laneHeightDp: androidx.compose.ui.unit.Dp,
    fontSize: TextUnit,
    onFinished: () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    var textWidth by remember { mutableStateOf(0) }
    // 长弹幕给更长的滚动时间，保证阅读速度大致一致。
    val durationMs = remember(entry.id) {
        (9_000 + entry.message.text.length * 120).coerceIn(9_000, 16_000)
    }

    LaunchedEffect(entry.id, textWidth, trackWidth) {
        if (textWidth <= 0 || trackWidth <= 0f) return@LaunchedEffect
        offsetX.snapTo(trackWidth)
        offsetX.animateTo(
            targetValue = -textWidth.toFloat(),
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
        )
        onFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = buildString {
                if (entry.message.userName.isNotBlank()) {
                    append(entry.message.userName).append("：")
                }
                append(entry.message.text)
            },
            color = entry.message.color.takeIf { it != 0 }?.let { Color(it) } ?: Color.White,
            fontSize = fontSize,
            maxLines = 1,
            // 低端 TV 盒上 blurRadius>0 会走 RenderEffect/MaskFilter，几十条弹幕同时
            // 滚动时每帧都要做模糊，很容易掉帧。改为零半径的偏移描边：视觉效果接近，
            // 但只是一次普通文字绘制。
            style = TextStyle(
                shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 0f),
            ),
            modifier = Modifier
                .offset(y = laneHeightDp * entry.lane)
                .onSizeChanged { textWidth = it.width }
                .graphicsLayer { translationX = offsetX.value },
        )
    }
}

private const val MAX_CONCURRENT = 24
