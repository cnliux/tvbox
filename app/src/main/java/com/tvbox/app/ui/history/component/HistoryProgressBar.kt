package com.tvbox.app.ui.history.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tvbox.app.ui.history.HistoryTokens

/**
 * 进度条：3dp 高，渐变填充；≥99.9% 时切灰紫完成态并关所有动画。
 * < 2% 且非直播 → 整条不画（刚点的还没起播，画空槽脏）。
 */
@Composable
fun HistoryProgressBar(progress: Float) {
    // progress < 0.02f 不画
    if (progress < 0.02f) return

    val done = progress >= 0.999f
    val track = if (done) HistoryTokens.ProgDoneA else Color(0xFF262626)
    val fillBrush = if (done) {
        Brush.horizontalGradient(listOf(HistoryTokens.ProgDoneA, HistoryTokens.ProgDoneB))
    } else {
        Brush.horizontalGradient(listOf(HistoryTokens.ProgFrom, HistoryTokens.ProgTo))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HistoryTokens.M.ProgH)
            .clip(RoundedCornerShape(2.dp))
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(HistoryTokens.M.ProgH)
                .background(fillBrush),
        )
    }
}
