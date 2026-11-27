package com.tvbox.app.ui

import com.tvbox.app.ui.theme.TvColors
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.LegalTerms
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.tvFocusScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T6-39 + S2-10：首次启动法律声明确认页。
 *
 * 行为约定：
 * - 不可跳过（BackHandler 拦截），除非 onAccepted 已被调用；
 * - 三项勾选项全部选中时「我已阅读并同意」按钮才可用；
 * - 提交后写入 SharedPreferences（tvbox_legal 文件），下次启动不再展示；
 * - DPAD / 键盘操作完全可用，焦点放大效果由 tvFocusScale 提供。
 */
@Composable
fun FirstRunScreen(onAccepted: suspend () -> Unit) {
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 防重复点击：提交中置灰并禁用 Back。
    var submitting by remember { mutableStateOf(false) }

    // 三项勾选项状态
    var checkOne by remember { mutableStateOf(false) }
    var checkTwo by remember { mutableStateOf(false) }
    var checkThree by remember { mutableStateOf(false) }

    val allChecked = checkOne && checkTwo && checkThree
    val acceptEnabled = allChecked && !submitting

    // 返回键拦截：法律声明落盘前（含 submitting 窗口）都不允许离开本页。
    androidx.activity.compose.BackHandler(enabled = true) { /* 不响应 */ }

    PageSurface { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // 标题
            Text(
                text = "聚合视频 合规使用声明",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请仔细阅读以下条款并逐项确认后继续",
                style = MaterialTheme.typography.bodyMedium,
                color = TvColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 条款正文：外层 Column 已整体 verticalScroll，这里**不能再嵌套滚动容器**——
            // 嵌套滚动会收到父级给出的无限高约束，ScrollNode 直接抛
            // IllegalStateException("Vertically scrollable component was measured with an
            // infinity maximum height constraints")，导致首启页启动即崩。
            // 因此仅保留 heightIn(min=260dp) 保证视觉最小高度，超出部分由外层滚动承载，
            // 小屏/大字号/横屏依然不裁切（M-02 意图不变）。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(min = 260.dp),
                color = TvColors.Surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = LegalTerms.TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 三项勾选项
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TermCheckbox(
                    label = "① 客户端能力边界：我理解本应用仅提供本地播放客户端，不内置/托管/推荐任何公开订阅地址。",
                    checked = checkOne,
                    onCheckedChange = { checkOne = it },
                )
                TermCheckbox(
                    label = "② 合规使用：我承诺不规避技术措施、不伪造平台登录态，并对所使用的源内容承担所在地区的合法授权责任。",
                    checked = checkTwo,
                    onCheckedChange = { checkTwo = it },
                )
                TermCheckbox(
                    label = "③ 下游分发约束：我知悉再分发必须保留本声明与 NOTICE，不得改名冒用，不得预置任何源。",
                    checked = checkThree,
                    onCheckedChange = { checkThree = it },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 确认按钮
            Button(
                onClick = {
                    if (!acceptEnabled) return@Button
                    submitting = true
                    focusManager.clearFocus()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            context.saveLegalAck()
                        }
                        onAccepted()
                    }
                },
                enabled = acceptEnabled,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在保存…")
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("我已阅读并同意，进入应用", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (!allChecked) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "完成全部勾选后方可继续",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.TextTertiary,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TermCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusScale(
                shape = MaterialTheme.shapes.medium,
                idleBorder = TvColors.Border.copy(alpha = 0.6f),
            )
            .clip(MaterialTheme.shapes.medium)
            .selectable(
                selected = checked,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            ),
        color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else TvColors.Surface,
        border = if (checked) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已勾选",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                // 未选中态：用空心方框绘制，保持与焦点放大一致的视觉层级。
                Surface(
                    modifier = Modifier.size(22.dp),
                    shape = MaterialTheme.shapes.small,
                    color = Color.Transparent,
                    border = BorderStroke(1.5.dp, TvColors.TextTertiary),
                ) {}
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (checked) Color.White else TvColors.TextSecondary,
            )
        }
    }
}

// ─── SharedPreferences 存取 ────────────────────────────────────────────────────

/**
 * 将法律条款确认结果持久化到 SharedPreferences。
 * key：legal.ack_version / legal.ack_hash（与 LegalTerms 约定的键名一致）。
 */
internal fun Context.saveLegalAck(version: Int = LegalTerms.TERMS_VERSION, hash: String = LegalTerms.CURRENT_HASH) {
    getSharedPreferences("tvbox_legal", Context.MODE_PRIVATE).edit().apply {
        putInt(LegalTerms.VERSION_KEY, version)
        putString(LegalTerms.HASH_KEY, hash)
        apply()
    }
}

/**
 * 读取法律条款确认状态；null 表示尚未确认过。
 */
internal fun Context.legalAck(): LegalTerms.Ack? {
    val prefs = getSharedPreferences("tvbox_legal", Context.MODE_PRIVATE)
    val v = prefs.getInt(LegalTerms.VERSION_KEY, -1)
    val h = prefs.getString(LegalTerms.HASH_KEY, null)
    return if (v >= 0 && !h.isNullOrBlank()) LegalTerms.Ack(v, h, 0L) else null
}
