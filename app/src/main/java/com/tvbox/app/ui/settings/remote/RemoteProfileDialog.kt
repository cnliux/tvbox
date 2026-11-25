package com.tvbox.app.ui.settings.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.RemoteKeyAction
import com.tvbox.app.domain.RemoteKeyBindings
import com.tvbox.app.domain.RemotePreset
import com.tvbox.app.domain.RemoteProfile
import com.tvbox.app.ui.theme.TvColors

/**
 * P1-09 的可见入口：出厂预设选择 + 按键学习记录 + `remote_profile.json` 导出 / 导入。
 *
 * 这里只负责把 [RemoteProfile] 编解码结果交给调用方落盘（AppSettings.remoteKeyBindings），
 * 不做文件权限 / URI 授权，避免引入 scoped storage 的复杂度；用户可在只读框里长按复制
 * 导出的 JSON，或从剪贴板粘贴导入。校验失败只显示错误文案，绝不改动现有绑定。
 */
@Composable
fun RemoteProfileDialog(
    bindings: RemoteKeyBindings,
    onApply: (RemoteKeyBindings) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val exported = remember(bindings) { RemoteProfile.fromBindings(bindings).toJson() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("遥控器预设与迁移") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "出厂预设（已学习的 scanCode / 设备映射会保留）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                RemotePreset.entries.forEach { preset ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // 预设换的是键位表与手感口径，硬件学习记录是用户资产，不能丢。
                            onApply(
                                preset.bindings.copy(
                                    scanCodes = bindings.scanCodes,
                                    deviceKeys = bindings.deviceKeys,
                                ),
                            )
                        },
                    ) {
                        Text("${preset.displayName} · ${preset.feel.summary()}")
                    }
                    Text(
                        text = preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TvColors.TextSecondary,
                    )
                }
                HorizontalDivider()
                Text(
                    text = "按键学习记录（scanCode）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                if (bindings.scanCodes.isEmpty()) {
                    Text(
                        text = "暂无记录：在「自定义键位绑定」里捕获按键时会同时记录 scanCode。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvColors.TextSecondary,
                    )
                } else {
                    bindings.scanCodes.forEach { (actionId, codes) ->
                        val action = RemoteKeyAction.fromId(actionId)
                        Text(
                            text = "${action?.displayName ?: actionId}：" +
                                codes.sorted().joinToString(" / ") { "0x%03X".format(it) },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    text = "导出 remote_profile.json（只读，可长按复制）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                TextField(
                    value = exported,
                    onValueChange = {},
                    readOnly = true,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "导入（粘贴完整 JSON，先验 checksum）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    Button(
                        onClick = {
                            val parsed = RemoteProfile.parse(draft)
                            val profile = parsed.profile
                            if (parsed.error != null || profile == null) {
                                message = parsed.error ?: "导入失败：配置为空"
                            } else {
                                onApply(profile.bindings)
                                message = "已导入预设：${RemotePreset.fromId(profile.preset)?.displayName ?: profile.preset}"
                            }
                        },
                    ) {
                        Text("导入并应用")
                    }
                }
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.startsWith("已导入")) TvColors.Accent else TvColors.Danger,
                    )
                }
            }
        },
    )
}
