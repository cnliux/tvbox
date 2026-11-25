package com.tvbox.app.ui.history.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.ui.history.HistoryTokens

/**
 * 分组头部：今天 / 昨天 / 本周 / 更早 + 右侧计数（如「今天（3）」）。
 *
 * ⚠️ 跨天分组键 Bug 已在 v1.0.33 修过（commit fix(history/probe): 跨天分组键修复）；
 *    重构时别把时区逻辑"顺手简化"回 LocalDate.now()——必须用现有的 history.updatedAtEpochMs
 *    路径，否则凌晨 00:07 看完的会跑进"昨天"。
 *
 * 算法一行不许改（只在调用方传 label/count，渲染换皮）。
 */
@Composable
fun SectionHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp, start = 4.dp, end = 16.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = "$label （$count）",
            fontSize = HistoryTokens.M.MetaSz,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.5.sp,
            color = HistoryTokens.MetaDim,
        )
    }
}
