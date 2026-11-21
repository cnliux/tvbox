package com.tvbox.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvbox.app.data.DlnaCaster
import com.tvbox.app.data.DlnaDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 手机端投屏控制器：把点播页那套 DLNA 发现/播放/停止状态抽成共享实现，
 * 供「电视直播」「平台直播」两个手机播放页复用，避免每个页面各写一份。
 *
 * 与点播页一样只在手机端启用；电视端不渲染投屏入口。
 */
internal class CastController(
    context: Context,
    private val scope: CoroutineScope,
    private val stopScope: CoroutineScope,
    private val onCastStopped: () -> Unit,
) {
    private val caster = DlnaCaster(context)

    var dialogVisible by mutableStateOf(false)
    var devices by mutableStateOf<List<DlnaDevice>>(emptyList())
    var discovering by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searchNonce by mutableIntStateOf(0)
    var castingDevice by mutableStateOf<DlnaDevice?>(null)
        private set
    /** 一次性提示，由外部 LaunchedEffect 消费（Toast）。 */
    var notice by mutableStateOf<String?>(null)

    val castLabel: String?
        get() = if (castingDevice != null) "停止投屏" else "投屏"

    fun onCastClick() {
        if (castingDevice != null) stopCasting() else dialogVisible = true
    }

    fun stopCasting() {
        val device = castingDevice ?: return
        caster.stopAsync(device, stopScope)
        castingDevice = null
        // 投屏开始时暂停了本机播放，停止后恢复，避免用户误以为直播卡住。
        onCastStopped()
        notice = "已停止投屏"
    }

    suspend fun discover() {
        discovering = true
        error = null
        runCatching { caster.discover() }
            .onSuccess { devices = it }
            .onFailure {
                if (it is CancellationException) throw it
                error = it.message ?: "搜索投屏设备失败"
            }
        discovering = false
    }

    /**
     * @param onStarted 投屏成功后回调，用于暂停本机播放器（避免本机与电视同时出声）。
     */
    fun startCasting(device: DlnaDevice, url: String, title: String, onStarted: () -> Unit) {
        scope.launch {
            error = null
            caster.play(device, url, title)
                .onSuccess {
                    castingDevice = device
                    onStarted()
                    dialogVisible = false
                    notice = "已投屏到 ${device.name}"
                }
                .onFailure { error = it.message ?: "投屏失败" }
        }
    }

    /** 页面销毁时使用：发出停止投屏请求后再释放页面持有的作用域。 */
    fun dispose() {
        val device = castingDevice
        if (device != null) {
            stopScope.launch { caster.stop(device) }
                .invokeOnCompletion { stopScope.cancel() }
        } else {
            stopScope.cancel()
        }
    }
}

@Composable
internal fun rememberCastController(
    onNotice: (String) -> Unit,
    onCastStopped: () -> Unit,
): CastController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stopScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    // 播放器可能因解码策略切换而重建；用 rememberUpdatedState 让回调始终作用于最新 player。
    val stoppedHandler by rememberUpdatedState(onCastStopped)
    val controller = remember(context, scope, stopScope) {
        CastController(context, scope, stopScope) { stoppedHandler() }
    }
    val noticeHandler by rememberUpdatedState(onNotice)

    LaunchedEffect(controller, controller.dialogVisible, controller.searchNonce) {
        if (!controller.dialogVisible) return@LaunchedEffect
        controller.discover()
    }
    LaunchedEffect(controller, controller.notice) {
        val text = controller.notice ?: return@LaunchedEffect
        noticeHandler(text)
        controller.notice = null
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}

/**
 * 投屏设备发现对话框。
 *
 * @param castUrl 每次点击设备时动态取当前播放地址：直播换线路后地址会变，不能用进入页面时的旧值。
 * 平台直播必须传「平台源地址」，不能传本机续流代理地址（电视无法访问 127.0.0.1）。
 */
@Composable
internal fun CastDeviceDialog(
    controller: CastController,
    castUrl: () -> String?,
    castTitle: () -> String,
    onCastingStarted: () -> Unit,
) {
    if (!controller.dialogVisible) return
    AlertDialog(
        onDismissRequest = { controller.dialogVisible = false },
        title = { Text("投屏到电视") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("请确保手机与电视连接同一 Wi-Fi，且电视已开启 DLNA/UPnP 投屏。")
                if (controller.discovering) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在搜索 DLNA/UPnP 设备…")
                }
                controller.error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                if (controller.devices.isNotEmpty()) {
                    controller.devices.forEach { device ->
                        Button(
                            onClick = {
                                val url = castUrl()
                                if (url.isNullOrBlank()) {
                                    controller.error = "当前线路暂无可投屏的播放地址"
                                } else {
                                    controller.startCasting(device, url, castTitle(), onCastingStarted)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(device.name)
                        }
                    }
                } else if (!controller.discovering) {
                    Text("未发现设备，请确认电视已开启 DLNA/UPnP 投屏后再重新搜索。")
                }
            }
        },
        confirmButton = {
            Button(onClick = { controller.searchNonce++ }) { Text("重新搜索") }
        },
        dismissButton = {
            Button(onClick = { controller.dialogVisible = false }) { Text("关闭") }
        },
    )
}
