package com.tvbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.ParentalLock
import com.tvbox.app.domain.PinEntry
import com.tvbox.app.ui.theme.TvColors

/** PIN 数字盘弹窗的两种模式。 */
enum class PinPadMode {
    /** 校验：输满 4 位即对已存摘要走 [ParentalLock.verifyPin]。 */
    Verify,

    /** 设置/更换：两次输入一致才把摘要（[ParentalLock.hashPin]）交回调落盘。 */
    Setup,
}

/**
 * T6-42 少儿锁（家长锁）4 位 PIN 数字盘弹窗。
 *
 * - DPAD 友好：3×4 数字格（1–9 / ⌫ / 0 / ✓）逐格可聚焦，方向键在格点间移动，
 *   OK/确认键触发当前聚焦格；BACK 由 AlertDialog 的 onDismissRequest 关闭；
 * - 进入弹窗后 60ms + 重试把首焦落到「1」（与直播面板同一 F-04 首焦模式）；
 * - [PinPadMode.Verify]：4 位输满立即校验，错误提示「PIN 不正确」并清空重输；
 * - [PinPadMode.Setup]：两次输入一致才调用 [onPinHashSaved]（只交摘要，
 *   明文不出本弹窗）；
 * - 输入状态用 [PinEntry] 持有，仅内存态，不落盘不进日志。
 */
@Composable
fun PinPadDialog(
    mode: PinPadMode,
    storedHash: String,
    onVerified: () -> Unit = {},
    onPinHashSaved: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var entry by remember { mutableStateOf(PinEntry("")) }
    var firstRound by remember { mutableStateOf(PinEntry("")) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var focusedCell by remember { mutableIntStateOf(0) }

    // 首焦锚点：挂在「1」格上，弹窗打开后延迟 60ms 请求焦点（低端机目标节点晚进组合时重试）。
    val firstCellFocus = rememberTvFocusRequest(key = Unit, delayMs = 60L, retries = 5)

    fun submit() {
        if (!entry.isComplete) return
        when (mode) {
            PinPadMode.Verify -> {
                if (ParentalLock.verifyPin(entry.entered, storedHash)) {
                    onVerified()
                } else {
                    error = "PIN 不正确"
                    entry = entry.reset()
                }
            }
            PinPadMode.Setup -> {
                if (!confirming) {
                    firstRound = entry
                    entry = entry.reset()
                    confirming = true
                    error = null
                } else {
                    if (firstRound.entered == entry.entered) {
                        onPinHashSaved(ParentalLock.hashPin(entry.entered))
                    } else {
                        error = "两次输入的 PIN 不一致"
                        confirming = false
                        firstRound = entry.reset()
                        entry = entry.reset()
                    }
                }
            }
        }
    }

    fun onCellPress(label: String) {
        when (label) {
            "⌫" -> {
                // 确认阶段空输入时退回第一轮，避免用户被卡死在"再输一遍"上。
                if (confirming && entry.entered.isEmpty()) {
                    confirming = false
                    firstRound = entry.reset()
                } else {
                    entry = entry.backspace()
                }
                error = null
            }
            "✓" -> submit()
            else -> {
                if (entry.isComplete) return
                entry = entry.append(label[0])
                error = null
                if (entry.isComplete) submit()
            }
        }
    }

    val padLabels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (mode) {
                    PinPadMode.Verify -> "请输入 PIN"
                    PinPadMode.Setup -> if (confirming) "再次输入 4 位数字 PIN" else "设置 4 位数字 PIN"
                },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 4 个指示点：随输入进度点亮。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    repeat(ParentalLock.PIN_LENGTH) { index ->
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < entry.entered.length) TvColors.Accent
                                    else Color.White.copy(alpha = 0.2f),
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                // 3×4 数字格：方向键逐格移动，确认键触发。
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (rowStart in 0 until padLabels.size step 3) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            for (column in 0 until 3) {
                                val index = rowStart + column
                                PinPadCell(
                                    label = padLabels[index],
                                    index = index,
                                    focused = focusedCell == index,
                                    onFocused = { focusedCell = it ?: -1 },
                                    onCellPress = { onCellPress(padLabels[index]) },
                                    modifier = if (index == 0) {
                                        Modifier.weight(1f).then(firstCellFocus.anchor)
                                    } else {
                                        Modifier.weight(1f)
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val hint = error ?: when (mode) {
                    PinPadMode.Verify -> "输错会自动清空重新输入"
                    PinPadMode.Setup -> if (confirming) "两次一致即保存" else "保存后遥控 4/5 入口将要求该 PIN"
                }
                Text(
                    text = hint,
                    color = if (error != null) TvColors.Danger else TvColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 单个数字格：聚焦/选中两档配色。
 *
 * modifier 顺序即 TV 焦点行为（同 [com.tvbox.app.ui.components.TvFocusableSurface] 约定）：
 * `aspectRatio -> clip -> background -> onFocusChanged -> clickable -> semantics -> focusable`，
 * 改动会破坏焦点框。`weight` 由调用方（RowScope）传入。
 */
@Composable
private fun PinPadCell(
    label: String,
    index: Int,
    focused: Boolean,
    onFocused: (Int?) -> Unit,
    onCellPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> TvColors.AccentSoft
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
            .onFocusChanged { onFocused(if (it.isFocused) index else null) }
            .clickable(onClick = onCellPress)
            .semantics {
                contentDescription = when (label) {
                    "⌫" -> "删除一位"
                    "✓" -> "确认"
                    else -> "数字 $label"
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) TvColors.AccentStrong else TvColors.TextPrimary,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
