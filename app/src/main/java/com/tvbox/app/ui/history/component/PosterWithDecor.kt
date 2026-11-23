package com.tvbox.app.ui.history.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tvbox.app.ui.components.rememberTvImageLoader
import com.tvbox.app.ui.history.CoverBadgeState
import com.tvbox.app.ui.history.CoverDecor
import com.tvbox.app.ui.history.CoverTagState
import com.tvbox.app.ui.history.HistoryTokens
import kotlin.math.PI
import kotlin.math.sin

/**
 * 书签形角标 Shape（右上圆角 + 左下圆角）。
 *
 * Clip 冲突注释（KDoc）：
 *   外层 Box 已 clip(RoundedCornerShape(HistoryTokens.M.Radius)) 保证海报四角圆润；
 *   角标必须位于该 clip 内部，只能贴右上/左下两角做差异化半径，**不能飘出**。
 *   想"飘出封面"得把角标提到 clip 外层再包 Box(propagateMinConstraints=false)，本次不改。
 *   shadow() 给角标加光晕会被 clip 吃掉——这是 Compose 第一大坑。要发光用 drawBehind {…}。
 */
private class BookmarkShape(
    private val topEndR: Dp,
    private val bottomStartR: Dp,
) : Shape {
    override fun outline(size: Size, layoutDirection: android.util.LayoutDirection, parent: Outline): Outline {
        // ⚠️ 这里无法直接拿到 Density —— Shape.outline() 是非 Composition 的普通方法。
        // 因此 radius 以 dp 值近似 px（大多数设备 ~1.5x）；对 8dp/6dp 这种小数值误差不超 1px，
        // 视觉上不影响。若后续需要精确控制，改用 Path.addRoundRect(FloatArray) + 手写密度换算。
        return RectangleOutline(Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, size.width, size.height),
                floatArrayOf(
                    0f, 0f,                            // topLeft = sharp
                    topEndR.value.dpToApproxPx(), 0f, // topRight ≈ radius
                    size.width, 0f,
                    size.width, size.height,           // bottomRight = sharp
                    size.width, size.height,
                    0f, size.height,
                    0f, size.height,                   // bottomLeft = sharp
                ),
            )
        })
    }

    /** dp → px 粗略换算：1dp ≈ 1.5px（mdpi=1× hdpi=1.5× xxhdpi=2× …）。实际设备误差在 ±1dp 内可接受。 */
    private fun Float.dpToApproxPx(): Float = this * 1.5f
}

@Composable
fun PosterWithDecor(
    itemUrl: String,
    decor: CoverDecor,
    modifier: Modifier = Modifier,
) {
    var posterFailed by remember(itemUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(HistoryTokens.M.Radius))
            .background(Color(0xFF2B5876)),
        contentAlignment = Alignment.Center,
    ) {
        if (itemUrl.isNotBlank() && !posterFailed) {
            AsyncImage(
                model = itemUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { posterFailed = true },
                imageLoader = rememberTvImageLoader(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF2B5876), Color(0xFF4E4376)))),
            )
        }

        // 顶部 34dp 黑渐变遮罩：浅金黄封面（如《完美世界》）上白字红标的救命层。
        // 删它要写理由：没这层必翻车。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HistoryTokens.M.ScrimH)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(HistoryTokens.ScrimTop, Color.Transparent),
                    ),
                ),
        )

        // 底部通栏 CoverTag。
        CoverTag(decor.tag, decor.latestEp)

        // 右上角角标（badge != None 才渲染）。
        if (decor.badge != CoverBadgeState.None) {
            Badge(badge = decor.badge, pulse = decor.pulse)
        }
    }
}

@Composable
private fun CoverTag(tag: CoverTagState, latestEp: Int?) {
    when (tag) {
        CoverTagState.None          -> Unit
        CoverTagState.CaughtUp      -> CaughtUpTag()
        CoverTagState.TodayPremiere -> TodayPremiereTag()
        CoverTagState.UpdatedTo     -> UpdatedToTag(latestEp)
    }
}

@Composable
private fun UpdatedToTag(latestEp: Int?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HistoryTokens.M.TagH)
            .align(Alignment.BottomCenter)
            .background(
                Brush.verticalGradient(
                    listOf(HistoryTokens.NewFrom.copy(alpha = 0.92f), HistoryTokens.NewTo.copy(alpha = 0.62f)),
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val label = if (latestEp != null && latestEp > 0) "更新至 $latestEp 集" else "有更新"
        Text(
            text = label,
            fontSize = HistoryTokens.M.BadgeSz,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun TodayPremiereTag() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HistoryTokens.M.TagH)
            .align(Alignment.BottomCenter)
            .background(
                Brush.verticalGradient(
                    listOf(HistoryTokens.NewFrom.copy(alpha = 0.92f), HistoryTokens.NewTo.copy(alpha = 0.62f)),
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp),
        ) {
            Canvas(modifier = Modifier.size(HistoryTokens.M.PremiereDot)) {
                drawCircle(color = Color.White.copy(alpha = 0.9f))
            }
            Text(
                text = "今日开播",
                fontSize = HistoryTokens.M.BadgeSz,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CaughtUpTag() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HistoryTokens.M.TagH)
            .align(Alignment.BottomCenter)
            .background(HistoryTokens.CaughtUpBg),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "已追平",
            fontSize = HistoryTokens.M.BadgeSz,
            color = HistoryTokens.Meta,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun Badge(badge: CoverBadgeState, pulse: Boolean) {
    Box(
        modifier = Modifier
            .offset(x = HistoryTokens.M.BadgeEdge, y = HistoryTokens.M.BadgeEdge)
            .size(26.dp)
            .semantics { role = Role.Image }
            .clip(BookmarkShape(topEndR = 6.dp, bottomStartR = 8.dp))
            .background(Brush.linearGradient(listOf(HistoryTokens.NewFrom, HistoryTokens.NewTo))),
        contentAlignment = Alignment.Center,
    ) {
        when (badge) {
            CoverBadgeState.Dot       -> PulseDot(pulse = pulse)
            CoverBadgeState.Count     -> DotBullet()
            CoverBadgeState.NinePlus  -> Text("9+", fontSize = HistoryTokens.M.BadgeSz, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            CoverBadgeState.None      -> Unit
        }
    }
}

@Composable
private fun PulseDot(pulse: Boolean) {
    if (!pulse) {
        Canvas(modifier = Modifier.size(HistoryTokens.M.DotSize)) {
            drawCircle(color = HistoryTokens.NewDot)
        }
        return
    }
    // 简化呼吸：基于 System.currentTimeMillis 的正弦波，1.8s 周期。
    // 注意：不在重组里产生副作用；LaunchedEffect 用于同步进度条之外的动效。
    val t = System.currentTimeMillis() / 1800f
    val scale = 1f + 0.18f * (0.5f + 0.5f * sin(t * 2 * PI))
    val alpha = 0.85f + 0.15f * (0.5f + 0.5f * sin(t * 2 * PI))
    Canvas(modifier = Modifier.size(HistoryTokens.M.DotSize)) {
        drawCircle(
            color = HistoryTokens.NewDot,
            radius = (size.minDimension / 2f) * scale,
            alpha = alpha,
        )
    }
}

@Composable
private fun DotBullet() {
    Canvas(modifier = Modifier.size(10.dp)) {
        drawCircle(color = Color.White.copy(alpha = 0.9f))
    }
}
