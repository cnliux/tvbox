package com.tvbox.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * S2-08：应用内「开源许可」页（TV 端用竖排 LazyColumn + DPAD 可 Focus，不用 WebView）。
 * 数据与 `tools/gen_oss_notices.py` 生成的 assets/oss_notices.json 是同一份。
 */
@Serializable
data class OssNotice(
    val name: String,
    val version: String,
    val license: String,
    val copyright: String,
    val url: String,
    val scope: String = "runtime",
)

@Composable
fun OssNoticesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember {
        runCatching {
            val raw = context.assets.open("oss_notices.json").bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<List<OssNotice>>(raw)
        }.getOrDefault(emptyList())
    }
    var selected: OssNotice? by remember { mutableStateOf(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "本项目基于 GPL-3.0 分发；下列第三方依赖的许可证与版权声明如下。",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(notices, key = { it.name }) { notice ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = notice }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(text = notice.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${notice.version} · ${notice.license}" +
                                    if (notice.scope == "test") " · 仅测试" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                selected?.let { notice ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("版权：${notice.copyright}", style = MaterialTheme.typography.bodySmall)
                        Text("项目页：${notice.url}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
