package com.tvbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.QuantitySetting
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens

/**
 * 设置页公共小件（原手机/TV 两份 private 实现的去重收敛）。
 *
 * 设计原则：
 * - 逐字相同的实现直接合并（分组分割线、确认弹窗）；
 * - 语义相同但外观/焦点行为不同的实现（HeroStat、文本编辑器、chips）参数化收敛，
 *   TV 端焦点行为（focusGroup / tvFocusScale）不与手机触摸目标强行统一；
 * - 本文件只依赖 domain 与 theme，不反向依赖 ui.settings，避免循环依赖。
 */

/** 分组内的缩进分割线（原 `DividerInGroup` / `TvDividerInGroup`，逐行相同）。 */
@Composable
fun SettingsGroupDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = TvDimens.SettingsGroupDividerIndent),
        color = TvColors.Border.copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
}

/**
 * 分组/落地页的状态小卡（原 `HeroStat` / `TvHeroStat`）。
 * 两端原本只差标签颜色档与数值是否单行省略，这里参数化收敛。
 */
@Composable
fun HeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelColor: Color = TvColors.TextSecondary,
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
    valueSingleLine: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(text = label, color = labelColor, style = labelStyle)
        Text(
            text = value,
            color = TvColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (valueSingleLine) 1 else Int.MAX_VALUE,
            overflow = if (valueSingleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

/**
 * 危险操作二次确认弹窗（原两设置页各写两遍的清空历史/线路统计 AlertDialog）。
 * 文案写明不可撤销后果由调用方传入；确认按钮走主题主色，取消走次要文字色。
 */
@Composable
fun DangerConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "清空",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TvColors.TextPrimary) },
        text = { Text(text, color = TvColors.TextSecondary) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.onPrimary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TvColors.TextSecondary) } },
    )
}

/**
 * 触摸版选项 chips（原手机端 private `ChoiceChips`）。
 * 48dp 最小触摸目标；未选中项用 TextPrimary 提亮，避免深色底上辨识不清。
 */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp, max = 240.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) TvColors.Accent.copy(alpha = 0.22f)
                        else TvColors.SurfaceRaised.copy(alpha = 0.75f)
                    )
                    .clickable(role = Role.Button) { onSelect(option) }
                    .semantics { stateDescription = if (isSelected) "已选中" else "未选中" }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) TvColors.Accent else TvColors.TextPrimary.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 遥控器版选项 chips（原 TV 端 private `TvChoiceChips`）。
 * focusGroup 保证方向键按视觉顺序移动，tvFocusScale 提供焦点放大；与触摸版外观刻意不同。
 */
@Composable
fun <T> TvChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val focusShape = RoundedCornerShape(8.dp)
    FlowRow(
        modifier = Modifier.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(focusShape)
                    .background(
                        if (isSelected) TvColors.Accent.copy(alpha = 0.18f)
                        else TvColors.SurfaceRaised.copy(alpha = 0.6f)
                    )
                    .tvFocusScale(shape = focusShape)
                    .clickable(role = Role.Button) { onSelect(option) }
                    .semantics { stateDescription = if (isSelected) "已选中" else "未选中" }
                    .focusable()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) TvColors.Accent else TvColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 数量类设置项的统一 chips：自动带上 [QuantitySetting.choices] 与「默认」标签逻辑。
 * [chips] 插槽由调用方选择触摸版 / 遥控器版，避免本组件依赖具体焦点实现。
 */
@Composable
fun QuantityChoiceChips(
    setting: QuantitySetting,
    selected: Int,
    onSelect: (Int) -> Unit,
    chips: @Composable (options: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) -> Unit =
        { options, selectedValue, label, select -> ChoiceChips(options, selectedValue, label, select) },
) {
    chips(setting.choices, selected, { if (it == setting.default) "默认" else "$it" }, onSelect)
}

/**
 * 文本型设置输入（原手机 private `TextValueEditor` / TV private `TvTextValueEditor`）。
 * 统一「先改草稿，点保存才写回」避免逐字符写盘；TV 端通过 [fieldModifier] 加
 * 固定宽度与 focusable、[saveButton] 插槽换成焦点按钮。
 */
@Composable
fun TextValueEditor(
    value: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    saveButton: @Composable RowScope.(onSaveDraft: () -> Unit) -> Unit = { onSaveDraft ->
        TextButton(onClick = onSaveDraft) { Text("保存", color = TvColors.Accent) }
    },
) {
    var draft by remember(value) { mutableStateOf(value) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            modifier = fieldModifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        saveButton { onSave(draft) }
    }
}
