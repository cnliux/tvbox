package com.tvbox.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvbox.app.ui.theme.TvColors

/**
 * 遥控器可聚焦「按钮面」的公共骨架。
 *
 * 收敛的是三处完全相同的焦点链：
 * `tvFocusScale -> clip -> onFocusChanged -> clickable -> (semantics) -> focusable`，
 * 以及「聚焦/选中/常态」三档配色。差异（形状、文案、文字样式、48dp 热区放在容器还是内容、
 * 语义标签、投影）全部通过参数或 [content] slot 保留，调用方拿到 [focused] 自行渲染内容。
 *
 * 注意：不要在此增加 `focusable()`、`focusGroup()` 或额外的 `focusRequester`；
 * modifier 顺序即 TV 焦点行为，改动会破坏现有焦点顺序和焦点框。
 */
@Stable
@Composable
fun TvFocusableSurface(
    shape: Shape,
    onClick: () -> Unit,
    focusedContainerColor: Color,
    idleContainerColor: Color,
    modifier: Modifier = Modifier,
    focusedContentColor: Color = TvColors.TextPrimary,
    idleContentColor: Color = TvColors.TextSecondary,
    focusedBorder: Color = TvColors.FocusRing,
    idleBorder: Color = TvColors.Border,
    selected: Boolean = false,
    selectedContainerColor: Color = TvColors.AccentSoft,
    selectedContentColor: Color = TvColors.AccentStrong,
    selectedIdleBorder: Color? = null,
    focusedTonalElevation: Dp = 0.dp,
    focusedShadowElevation: Dp = 0.dp,
    /** 放在 Surface 上的最小高度（键盘按键：焦点框包住 48dp）。 */
    containerMinHeight: Dp = Dp.Unspecified,
    /** 放在内容 Box 上的最小高度（顶栏按钮 / 分类胶囊）。 */
    contentMinHeight: Dp = Dp.Unspecified,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.Center,
    descriptionText: String? = null,
    stateText: String? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .then(
                if (containerMinHeight != Dp.Unspecified) {
                    Modifier.heightIn(min = containerMinHeight)
                } else {
                    Modifier
                },
            )
            .tvFocusScale(
                shape = shape,
                focusedBorder = focusedBorder,
                idleBorder = if (selected) selectedIdleBorder ?: idleBorder else idleBorder,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(role = Role.Button, onClick = onClick)
            .let { base ->
                // 无语义标签时保持原有 modifier 链，不额外插入语义节点。
                if (descriptionText == null && stateText == null) {
                    base
                } else {
                    base.semantics {
                        descriptionText?.let { contentDescription = it }
                        stateText?.let { stateDescription = it }
                    }
                }
            }
            .focusable(),
        shape = shape,
        color = when {
            // 选中态优先于焦点态：焦点环由 tvFocusScale 单独绘制，不能被聚焦配色吞掉。
            focused && selected -> selectedContainerColor
            focused -> focusedContainerColor
            selected -> selectedContainerColor
            else -> idleContainerColor
        },
        contentColor = when {
            focused && selected -> selectedContentColor
            focused -> focusedContentColor
            selected -> selectedContentColor
            else -> idleContentColor
        },
        tonalElevation = if (focused) focusedTonalElevation else 0.dp,
        shadowElevation = if (focused) focusedShadowElevation else 0.dp,
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (contentMinHeight != Dp.Unspecified) {
                        Modifier.heightIn(min = contentMinHeight)
                    } else {
                        Modifier
                    },
                )
                .padding(contentPadding),
            contentAlignment = contentAlignment,
        ) {
            content(focused)
        }
    }
}
