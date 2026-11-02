package com.tvbox.app.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tvbox.app.domain.LivePlaybackWatchdog
import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.LiveLineFallbackReason
import com.tvbox.app.domain.PlaybackBufferDecision
import com.tvbox.app.domain.PlaybackBufferMonitor
import com.tvbox.app.domain.probeLiveLineMimeType
import android.widget.Toast
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.PageSurface
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val session = rememberPlaybackSession()
    BindPlaybackSession(session, null)
    PageSurface { padding ->
        when {
            state.liveLoading -> LoadingState(
                text = "正在加载直播源",
                modifier = Modifier.padding(padding),
            )
            state.liveError != null -> ErrorState(
                message = state.liveError,
                onRetry = actions::refreshLive,
                modifier = Modifier.padding(padding),
            )
            state.liveChannels.isEmpty() -> ErrorState(
                message = "没有可用直播频道",
                onRetry = actions::refreshLive,
                modifier = Modifier.padding(padding),
            )
            else -> LivePlayerScreen(state = state, actions = actions, session = session)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LivePlayerScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    session: PlaybackSession,
) {
    KeepScreenOnWhileVisible()
    val channels = state.liveChannels
    val currentChannel = channels[state.liveChannelIndex.coerceIn(0, channels.lastIndex)]
    val currentLine = currentChannel.lines[state.liveLineIndex.coerceIn(0, currentChannel.lines.lastIndex)]
    val latestLineUrl = rememberUpdatedState(currentLine.url)
    var showUrlOverlay by remember { mutableStateOf(false) }
    var showLineControl by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
    val isTelevision = state.appSettings.deviceMode == DeviceMode.Television
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val initialRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    // 手机模式下直播播放页全屏展示，隐藏状态栏与导航栏，退出时恢复。
    if (isMobile) {
        FullscreenWhilePlayingPageVisible(activity)
    }
    val playerFocusRequester = remember { FocusRequester() }
    BindPlaybackSession(session, player)
    val bufferMonitor = remember {
        PlaybackBufferMonitor(
            continuousBufferThresholdMs = LIVE_CONTINUOUS_BUFFER_THRESHOLD_MS,
            frequentBufferWindowMs = LIVE_FREQUENT_BUFFER_WINDOW_MS,
            frequentBufferCount = LIVE_FREQUENT_BUFFER_COUNT,
            cumulativeBufferThresholdMs = LIVE_CUMULATIVE_BUFFER_THRESHOLD_MS,
        )
    }
    val playbackWatchdog = remember { LivePlaybackWatchdog() }
    var channelListVisible by remember { mutableStateOf(false) }
    var channelListInteraction by remember { mutableIntStateOf(0) }
    var mobileControlsVisible by remember(isTelevision) { mutableStateOf(!isTelevision) }
    var mobileControlsInteraction by remember { mutableIntStateOf(0) }
    // 手机"全屏"：把横屏直播画面裁剪铺满整个屏幕，消除上下黑边。
    var fullscreenZoom by remember { mutableStateOf(false) }
    var channelNumberInput by remember { mutableStateOf("") }
    var channelNumberNonce by remember { mutableIntStateOf(0) }
    var promptMessage by remember { mutableStateOf("") }
    var promptNonce by remember { mutableIntStateOf(0) }
    var promptVisible by remember { mutableStateOf(false) }
    var channelBadgeVisible by remember { mutableStateOf(true) }
    var bufferingLineUrl by remember { mutableStateOf<String?>(null) }
    var bufferingCheckNonce by remember { mutableIntStateOf(0) }
    var automaticSwitchLineUrl by remember { mutableStateOf<String?>(null) }
    // 播放器回调注册在 DisposableEffect(player) 里，只会捕获首次组合时的频道快照；
    // 换台后日志里的频道名/线路号会停留在旧值，排查时极易误判。改用更新态读取。
    val latestChannelLabel = rememberUpdatedState("${currentChannel.groupName}/${currentChannel.name}")
    val latestLineIndex = rememberUpdatedState(state.liveLineIndex)
    val latestLineCount = rememberUpdatedState(currentChannel.lines.size)

    fun showPrompt(message: String) {
        promptMessage = message
        promptVisible = true
        promptNonce++
    }

    fun showChannelList() {
        channelListVisible = true
        actions.showLivePanelGroups()
        channelListInteraction++
    }

    fun keepChannelListVisibleWhileBrowsing() {
        if (channelListVisible) channelListInteraction++
    }

    fun showMobileControls() {
        if (!isTelevision) {
            mobileControlsVisible = true
            mobileControlsInteraction++
        }
    }

    fun handleNumberKey(number: Int): Boolean {
        channelNumberInput = (channelNumberInput + number.toString()).takeLast(MAX_CHANNEL_NUMBER_DIGITS)
        showPrompt("频道 $channelNumberInput")
        channelNumberNonce++
        return true
    }

    fun switchLiveLineAfterIssue(reason: LiveLineFallbackReason) {
        val lineUrl = latestLineUrl.value
        android.util.Log.w(
            LIVE_LOG_TAG,
            "【触发线路容错】reason=$reason channel=${latestChannelLabel.value}, currentLineIndex=${latestLineIndex.value}, totalLines=${latestLineCount.value}, currentUrl=$lineUrl, canPlay=${session.canPlay}"
        )
        if (!session.canPlay) {
            android.util.Log.w(LIVE_LOG_TAG, "【容错跳过】session.canPlay=false")
            return
        }
        if (latestLineCount.value <= 1 && reason == LiveLineFallbackReason.Error) {
            // 当前频道只有一条线路且播放器硬错误：直接换台，避免手机端长时间黑屏。
            android.util.Log.w(LIVE_LOG_TAG, "【自动切换】单线频道硬错误，切换到下一台")
            actions.playNextLiveChannel()
            showPrompt("当前频道无法播放，已自动切换到下一台")
            return
        }
        val result = actions.advanceLiveLineAfterFailure(reason)
        android.util.Log.i(LIVE_LOG_TAG, "【自动容错】advanceLiveLineAfterFailure($reason) result=$result")
    }

    fun handleBufferDecision(decision: PlaybackBufferDecision?) {
        if (decision != null) {
            switchLiveLineAfterIssue(LiveLineFallbackReason.Stall)
        }
    }

    var lastExitBackPressTime by remember { mutableStateOf(0L) }
    var showExitLiveConfirm by remember { mutableStateOf(false) }
    // 在 Compose 树顶层渲染确认弹窗，避免在 LaunchedEffect/BackHandler 回调里直接调 showDialog。
    if (showExitLiveConfirm) {
        AlertDialog(
            onDismissRequest = { showExitLiveConfirm = false },
            title = { Text("退出直播") },
            text = { Text("确定要退出当前直播吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitLiveConfirm = false
                        actions.goBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760)),
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitLiveConfirm = false }) { Text("取消") }
            },
        )
    }
    BackHandler {
        android.util.Log.d(LIVE_LOG_TAG, "【返回键】页面级返回, channelListVisible=$channelListVisible, isMobile=$isMobile")
        // 手机模式用确认弹窗代替 Toast，避免误触退出。
        if (isMobile) {
            showExitLiveConfirm = true
        } else {
            actions.goBack()
        }
    }

    BackHandler(enabled = channelListVisible) {
        // 两级返回：频道列表 → 分类列表 → 关闭面板。
        android.util.Log.d(
            LIVE_LOG_TAG,
            "【返回键】面板内 stage=${state.liveGroupPanelStage}",
        )
        if (state.liveGroupPanelStage == LiveGroupStage.Channels) {
            actions.backToLiveGroups()
        } else {
            channelListVisible = false
            showMobileControls()
            if (isTelevision) playerFocusRequester.requestFocus()
        }
    }

    DisposableEffect(activity, initialRequestedOrientation, isTelevision) {
        activity?.requestedOrientation = if (isTelevision) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            activity?.requestedOrientation = initialRequestedOrientation
        }
    }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e(
                    LIVE_LOG_TAG,
                    "【播放器错误】code=${error.errorCode}, name=${error.errorCodeName}, msg=${error.message}, cause=${error.cause?.message}, channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, url=$latestLineUrl.value",
                    error
                )
                switchLiveLineAfterIssue(LiveLineFallbackReason.Error)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                android.util.Log.d(LIVE_LOG_TAG, "【播放就绪状态】playWhenReady=$playWhenReady, reason=$reason")
                if (!playWhenReady) {
                    bufferMonitor.onPaused()
                    playbackWatchdog.onPaused()
                    bufferingLineUrl = null
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                android.util.Log.d(
                    LIVE_LOG_TAG,
                    "【播放状态】$stateName, playWhenReady=${player.playWhenReady}, position=${player.currentPosition}, buffered=${player.bufferedPercentage}%, url=$latestLineUrl.value"
                )
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        android.util.Log.w(LIVE_LOG_TAG, "【开始缓冲】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, url=$latestLineUrl.value")
                        handleBufferDecision(
                            bufferMonitor.onBuffering(
                                playWhenReady = player.playWhenReady,
                                nowMs = System.currentTimeMillis(),
                            ),
                        )
                        if (player.playWhenReady && bufferingLineUrl != latestLineUrl.value) {
                            bufferingLineUrl = latestLineUrl.value
                            bufferingCheckNonce++
                        }
                    }
                    Player.STATE_READY -> {
                        android.util.Log.i(LIVE_LOG_TAG, "【播放就绪】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, buffered=${player.bufferedPercentage}%, url=$latestLineUrl.value")
                        bufferingLineUrl = null
                        handleBufferDecision(
                            bufferMonitor.onReady(
                                playWhenReady = player.playWhenReady,
                                nowMs = System.currentTimeMillis(),
                            ).decision,
                        )
                    }
                    Player.STATE_ENDED -> {
                        android.util.Log.w(LIVE_LOG_TAG, "【播放结束】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, url=$latestLineUrl.value")
                        bufferingLineUrl = null
                        // 直播 ENDED 多为流被服务端正常关闭（token 到期），先原地重连同一条线路。
                        switchLiveLineAfterIssue(LiveLineFallbackReason.Ended)
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(currentLine.url, state.liveReconnectNonce) {
        android.util.Log.d(
            LIVE_LOG_TAG,
            "【切换线路】channel=${currentChannel.name}, group=${currentChannel.groupName}, lineIndex=${state.liveLineIndex}, protocol=${currentLine.protocolHint}, headers=${currentLine.headers.keys}, nonce=${state.liveReconnectNonce}"
        )
        bufferMonitor.onMediaChanged()
        playbackWatchdog.onMediaChanged()
        bufferingLineUrl = null
        automaticSwitchLineUrl = null
        channelBadgeVisible = true
        // 逐线路下发 UA / Referer / Cookie：M3U 的 #EXTVLCOPT 头部只对所在那条线路生效，
        // 播放器若用全局默认 UA，虎牙/咪咕一类校验 Referer 的线路会直接 403 或黑屏。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(LIVE_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(LIVE_READ_TIMEOUT_MS)
        val safeHeaders = currentLine.headers
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() && value.none { ch -> ch == '\r' || ch == '\n' } }
        // User-Agent 属于 ExoPlayer 的受限请求头，走通用请求头会被静默丢弃，必须单独设置。
        safeHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.let { httpFactory.setUserAgent(it.value) }
        val otherHeaders = safeHeaders.filter { !it.key.equals("User-Agent", ignoreCase = true) }
        if (otherHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(otherHeaders)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
        // 无扩展名地址（https://cdn/play?id=cctv1）此前会被当成 progressive 流并嗅探失败，
        // 报 ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED 而"必然黑屏"；探测出真实类型后
        // 通过 MediaItem.mimeType 让 ExoPlayer 选择 HlsMediaSource / 对应抽取器。
        val mime = probeLiveLineMimeType(currentLine.url, safeHeaders)
        val mediaItem = MediaItem.Builder()
            .setUri(currentLine.url)
            .apply { if (mime.isNotBlank()) setMimeType(mime) }
            .build()
        android.util.Log.d(LIVE_LOG_TAG, "【取流】mime=${mime.ifBlank { "未探测" }}, protocolHint=${currentLine.protocolHint}")
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
        session.preparePlayback(player)
        delay(CHANNEL_BADGE_HIDE_DELAY_MS)
        channelBadgeVisible = false
    }

    LaunchedEffect(bufferingCheckNonce, bufferingLineUrl) {
        val watchedLineUrl = bufferingLineUrl ?: return@LaunchedEffect
        delay(LIVE_CONTINUOUS_BUFFER_THRESHOLD_MS)
        if (
            bufferingLineUrl == watchedLineUrl &&
            latestLineUrl.value == watchedLineUrl &&
            player.playbackState == Player.STATE_BUFFERING
        ) {
            handleBufferDecision(
                bufferMonitor.onBuffering(
                    playWhenReady = player.playWhenReady,
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    LaunchedEffect(player, currentLine.url) {
        while (true) {
            delay(LIVE_PROGRESS_SAMPLE_INTERVAL_MS)
            val playing = player.isPlaying
            val pos = player.currentPosition
            val statePlayback = player.playbackState
            android.util.Log.v(
                LIVE_LOG_TAG,
                "【进度采样】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, playing=$playing, state=$statePlayback, pos=$pos, buffered=${player.bufferedPercentage}%"
            )
            if (
                playbackWatchdog.onSample(
                    isPlaying = playing,
                    positionMs = pos,
                    nowMs = System.currentTimeMillis(),
                )
            ) {
                android.util.Log.e(LIVE_LOG_TAG, "【看门狗触发】长时间无进度，判定卡死，先原地重连")
                switchLiveLineAfterIssue(LiveLineFallbackReason.Stall)
            }
        }
    }

    LaunchedEffect(Unit) {
        playerFocusRequester.requestFocus()
    }

    // ViewModel 侧的容错/订阅切换结果通过 liveNotice 下发，这里统一转成轻提示并消费。
    LaunchedEffect(state.liveNotice) {
        val notice = state.liveNotice ?: return@LaunchedEffect
        showPrompt(notice)
        actions.consumeLiveNotice()
    }

    LaunchedEffect(isTelevision, channelListVisible, channelListInteraction) {
        if (!isTelevision || !channelListVisible) return@LaunchedEffect
        delay(CHANNEL_LIST_HIDE_DELAY_MS)
        channelListVisible = false
        playerFocusRequester.requestFocus()
    }

    LaunchedEffect(isTelevision, mobileControlsVisible, mobileControlsInteraction, channelListVisible) {
        if (isTelevision || !mobileControlsVisible || channelListVisible) return@LaunchedEffect
        delay(MOBILE_CONTROLS_HIDE_DELAY_MS)
        mobileControlsVisible = false
    }

    LaunchedEffect(channelNumberNonce) {
        if (channelNumberNonce == 0) return@LaunchedEffect
        delay(CHANNEL_NUMBER_COMMIT_DELAY_MS)
        val requestedChannel = channelNumberInput.toIntOrNull()
        channelNumberInput = ""
        if (requestedChannel == null || !actions.selectLiveChannelNumber(requestedChannel)) {
            showPrompt("无频道内容")
        } else {
            promptVisible = false
        }
    }

    PromptTimer(nonce = promptNonce, hideDelayMs = PROMPT_HIDE_DELAY_MS) { promptVisible = false }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .pointerInput(channels.size, isTelevision) {
                detectTapGestures(
                    onTap = {
                        if (isTelevision) {
                            showChannelList()
                        } else {
                            mobileControlsVisible = !mobileControlsVisible
                            if (mobileControlsVisible) mobileControlsInteraction++
                        }
                    },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) {
                            actions.playPreviousLiveChannel()
                            showPrompt("上一个频道")
                        } else {
                            actions.playNextLiveChannel()
                            showPrompt("下一个频道")
                        }
                        showMobileControls()
                    },
                )
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                // 面板打开时方向键/确认键交给列表自身的焦点系统，只顺手重置自动隐藏计时；
                // 若在根节点把 D-PAD 吞掉换台，分类/频道列表将无法用遥控器操作。
                if (channelListVisible) {
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_MENU,
                        AndroidKeyEvent.KEYCODE_INFO,
                        -> {
                            channelListVisible = false
                            if (isTelevision) playerFocusRequester.requestFocus()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP,
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER,
                        -> {
                            keepChannelListVisibleWhileBrowsing()
                            false
                        }
                        else -> false
                    }
                } else when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        actions.playPreviousLiveChannel()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        actions.playNextLiveChannel()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> {
                        if (session.interrupted) {
                            player.seekToDefaultPosition()
                            session.play(player)
                        } else {
                            showLineControl = true
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MENU,
                    AndroidKeyEvent.KEYCODE_INFO,
                    -> {
                        if (channelListVisible) {
                            channelListVisible = false
                            if (isTelevision) playerFocusRequester.requestFocus()
                        } else {
                            showChannelList()
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (channelListVisible) {
                            actions.playPreviousLiveChannel()
                            keepChannelListVisibleWhileBrowsing()
                        } else {
                            actions.playPreviousLiveLine()
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (channelListVisible) {
                            actions.playNextLiveChannel()
                            keepChannelListVisibleWhileBrowsing()
                        } else {
                            actions.playNextLiveLine()
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_0,
                    AndroidKeyEvent.KEYCODE_NUMPAD_0,
                    -> handleNumberKey(0)
                    AndroidKeyEvent.KEYCODE_1,
                    AndroidKeyEvent.KEYCODE_NUMPAD_1,
                    -> handleNumberKey(1)
                    AndroidKeyEvent.KEYCODE_2,
                    AndroidKeyEvent.KEYCODE_NUMPAD_2,
                    -> handleNumberKey(2)
                    AndroidKeyEvent.KEYCODE_3,
                    AndroidKeyEvent.KEYCODE_NUMPAD_3,
                    -> handleNumberKey(3)
                    AndroidKeyEvent.KEYCODE_4,
                    AndroidKeyEvent.KEYCODE_NUMPAD_4,
                    -> handleNumberKey(4)
                    AndroidKeyEvent.KEYCODE_5,
                    AndroidKeyEvent.KEYCODE_NUMPAD_5,
                    -> handleNumberKey(5)
                    AndroidKeyEvent.KEYCODE_6,
                    AndroidKeyEvent.KEYCODE_NUMPAD_6,
                    -> handleNumberKey(6)
                    AndroidKeyEvent.KEYCODE_7,
                    AndroidKeyEvent.KEYCODE_NUMPAD_7,
                    -> handleNumberKey(7)
                    AndroidKeyEvent.KEYCODE_8,
                    AndroidKeyEvent.KEYCODE_NUMPAD_8,
                    -> handleNumberKey(8)
                    AndroidKeyEvent.KEYCODE_9,
                    AndroidKeyEvent.KEYCODE_NUMPAD_9,
                    -> handleNumberKey(9)
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    isFocusable = false
                    isFocusableInTouchMode = false
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            update = {
                it.player = player
                it.resizeMode = if (fullscreenZoom) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (session.interrupted) {
            ControlButton("继续播放", {
                player.seekToDefaultPosition()
                session.play(player)
            }, Modifier.align(Alignment.Center), primary = true)
        }
        // 手机模式常驻显示频道名与线路信息，方便全屏时确认当前频道。
        if (!channelListVisible && (if (isTelevision) channelBadgeVisible else true)) {
            LiveChannelBadge(
                channel = currentChannel,
                lineIndex = state.liveLineIndex,
                lineCount = currentChannel.lines.size,
                channelCount = channels.size,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(20.dp),
            )
        }
        if (!isTelevision) {
            ControlButton(
                text = "返回",
                onClick = { actions.goBack() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
            )
        }
        if (channelListVisible) {
            val channelPanelModifier = when {
                !isTelevision && isPortrait -> Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(MOBILE_PORTRAIT_CHANNEL_PANEL_HEIGHT_FRACTION)
                isTelevision -> Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(280.dp)
                else -> Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width((maxWidth * MOBILE_LANDSCAPE_CHANNEL_PANEL_WIDTH_FRACTION).coerceIn(240.dp, 340.dp))
            }
            LiveTwoLevelPanel(
                state = state,
                actions = actions,
                isPortraitPhone = !isTelevision && isPortrait,
                onClosePanel = {
                    channelListVisible = false
                    showMobileControls()
                    playerFocusRequester.requestFocus()
                },
                onChannelPicked = { index ->
                    actions.selectLiveChannel(index)
                    channelListVisible = false
                    showPrompt(channels[index].name)
                    showMobileControls()
                    if (isTelevision) playerFocusRequester.requestFocus()
                },
                onInteraction = ::keepChannelListVisibleWhileBrowsing,
                modifier = channelPanelModifier,
            )
        }
        if (!isTelevision && mobileControlsVisible && !channelListVisible) {
            MobileLiveControls(
                isPortrait = isPortrait,
                channelName = currentChannel.name,
                lineLabel = "${currentChannel.groupName} · 线路 ${state.liveLineIndex + 1}/${currentChannel.lines.size}",
                orientationActionLabel = if (isPortrait) "横屏" else "自动",
                onPrevious = {
                    actions.playPreviousLiveChannel()
                    showPrompt("上一个频道")
                    showMobileControls()
                },
                onChannels = {
                    showChannelList()
                    mobileControlsVisible = false
                },
                onNext = {
                    actions.playNextLiveChannel()
                    showPrompt("下一个频道")
                    showMobileControls()
                },
                onNextLine = {
                    showPrompt(if (actions.playNextLiveLine()) "正在切换线路" else "当前频道只有一条线路")
                    showMobileControls()
                },
                onRefresh = actions::refreshLive,
                onFullscreen = {
                    fullscreenZoom = !fullscreenZoom
                    showPrompt(if (fullscreenZoom) "全屏铺满" else "恢复原始比例")
                    showMobileControls()
                },
                fullscreenActionLabel = if (fullscreenZoom) "还原" else "全屏",
                onOrientation = {
                    activity?.requestedOrientation = if (isPortrait) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    showMobileControls()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
        if (promptVisible && promptMessage.isNotBlank()) {
            LivePrompt(
                message = promptMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = if (!isTelevision && mobileControlsVisible) 132.dp else 80.dp),
            )
        }

        if (showUrlOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .pointerInput(Unit) { detectTapGestures { showUrlOverlay = false } }
                    .padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("当前播放真实 URL", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = currentLine.url,
                        color = Color(0xFF7AE2BA),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .padding(16.dp)
                    )
                    Text(
                        text = "频道：${currentChannel.name} | 分组：${currentChannel.groupName} | 线路：${state.liveLineIndex + 1}/${currentChannel.lines.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("点击屏幕关闭", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (showLineControl) {
            androidx.activity.compose.BackHandler { showLineControl = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .pointerInput(Unit) { detectTapGestures { showLineControl = false } }
            ) {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(if (isTelevision) 420.dp else 360.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    color = Color(0xFF1A1A1A),
                    contentColor = Color.White
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("线路切换 - ${currentChannel.name}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(currentChannel.lines) { index, line ->
                                val selected = index == state.liveLineIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0xFF7AE2BA).copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable {
                                            // 只改状态：URL 变化会驱动上方取流逻辑（含逐线路 header、看门狗复位），
                                            // 这里不再重复 prepare，避免同一线路被 prepare 两次。
                                            actions.selectLiveLine(index)
                                            showLineControl = false
                                            showPrompt("已切换到线路 ${index + 1}")
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${index + 1}", modifier = Modifier.width(24.dp), color = if (selected) Color(0xFF7AE2BA) else Color.White.copy(alpha = 0.6f))
                                    // 展示源里真实线路名（如"线路3"/"电信"），并附协议标识便于排查取流失败。
                                    Text(
                                        text = line.name + if (line.protocolHint.isNotBlank()) " · ${line.protocolHint}" else "",
                                        modifier = Modifier.weight(1f),
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected) Text("当前", color = Color(0xFF7AE2BA), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.TextButton(onClick = {
                                val clip = android.content.ClipData.newPlainText("live_url", currentLine.url)
                                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                                showPrompt("URL 已复制到剪贴板")
                            }) { Text("复制 URL") }
                            androidx.compose.material3.TextButton(onClick = { showUrlOverlay = true; showLineControl = false }) { Text("查看完整 URL") }
                            androidx.compose.material3.TextButton(onClick = { showLineControl = false }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChannelBadge(
    channel: LiveChannel,
    lineIndex: Int,
    lineCount: Int,
    channelCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = channel.name,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${channel.groupName} · ${channel.number}/$channelCount · 线路 ${lineIndex + 1}/$lineCount",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveTwoLevelPanel(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    isPortraitPhone: Boolean,
    onClosePanel: () -> Unit,
    onChannelPicked: (Int) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channels = state.liveChannels
    val groups = channels.map { it.groupName }.distinct()
    val inChannelsStage = state.liveGroupPanelStage == LiveGroupStage.Channels
    val groupIndex = state.liveGroupIndex.coerceIn(0, groups.size)
    // 分类过滤后的频道；"全部"（index 0）时展示全量。
    val visibleChannels = if (groupIndex == 0) {
        channels
    } else {
        val name = groups[groupIndex - 1]
        channels.filter { it.groupName == name }
    }
    val groupListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val groupFocusRequester = remember { FocusRequester() }
    val channelFocusRequester = remember { FocusRequester() }
    val safeSelectedIndex = state.liveChannelIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0))
    val visibleSelectedIndex = visibleChannels.indexOfFirst { it.number == channels.getOrNull(safeSelectedIndex)?.number }
    // 焦点落点：优先当前播放频道；进入的分类不含当前频道时回落到首行，
    // 否则没有任何行挂上 FocusRequester，遥控器方向键会"失灵"。
    val focusTargetIndex = if (visibleSelectedIndex >= 0) visibleSelectedIndex else 0

    // 进入频道层级时才把列表定位到焦点行，并把焦点交给它，保证遥控器可直接上下选台。
    LaunchedEffect(inChannelsStage, groupIndex) {
        if (!inChannelsStage) return@LaunchedEffect
        channelListState.scrollToItem(focusTargetIndex)
        // LazyColumn 目标项要先完成组合才能挂上 FocusRequester。
        delay(80)
        runCatching { channelFocusRequester.requestFocus() }
            .onSuccess { android.util.Log.d(LIVE_LOG_TAG, "【焦点】已转移到频道列表项 #$focusTargetIndex（当前播放频道命中=${visibleSelectedIndex >= 0}）") }
            .onFailure { android.util.Log.w(LIVE_LOG_TAG, "【焦点】频道列表焦点请求失败：${it.message}") }
    }
    // 分类层级：打开面板回到当前选中分类并把焦点交给它。
    LaunchedEffect(inChannelsStage) {
        if (inChannelsStage) return@LaunchedEffect
        groupListState.scrollToItem(groupIndex)
        delay(80)
        runCatching { groupFocusRequester.requestFocus() }
            .onSuccess { android.util.Log.d(LIVE_LOG_TAG, "【焦点】已转移到分类项 #$groupIndex") }
            .onFailure { android.util.Log.w(LIVE_LOG_TAG, "【焦点】分类列表焦点请求失败：${it.message}") }
    }

    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        onInteraction()
                    }
                }
            },
        shape = if (isPortraitPhone) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        } else {
            RoundedCornerShape(0.dp)
        },
        color = Color(0xEF101514),
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (isPortraitPhone) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                    .size(width = 36.dp, height = 3.dp).background(Color.White.copy(alpha = 0.25f), CircleShape))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (inChannelsStage) {
                        Text(
                            text = groups.getOrNull(groupIndex - 1) ?: "全部频道",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text("直播分类", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = if (inChannelsStage) {
                            // Phone 触摸端与 TV 遥控器端提示用语不同，避免手机上看到"确定键"这类遥控器术语。
                            "共 ${visibleChannels.size} 个频道 · " +
                                if (isPortraitPhone) "点按选频道，返回回分类" else "返回键回分类"
                        } else {
                            "共 ${groups.size} 个分类 · " +
                                if (isPortraitPhone) "点按进入频道" else "确定键进入频道"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
                if (inChannelsStage) {
                    ControlButton("分类", { actions.backToLiveGroups() })
                }
                ControlButton("关闭", onClosePanel)
            }
            // 订阅切换入口：仅分类层级展示，切换后分类保持同名恢复（详见 selectLiveSource）。
            if (!inChannelsStage) {
                val sourceLabels = actions.liveSourceLabels()
                if (sourceLabels.size > 1) {
                    val currentIndex = state.liveSelectedSourceIndex.coerceIn(0, sourceLabels.lastIndex)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "订阅：${sourceLabels[currentIndex]}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ControlButton("切换", { actions.cycleLiveSource(1) })
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            if (!inChannelsStage) {
                LazyColumn(
                    state = groupListState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(listOf("全部") + groups, key = { index, name -> "g-$index-$name" }) { index, name ->
                        val playingGroupIndex = channels.getOrNull(safeSelectedIndex)
                            ?.let { current -> groups.indexOf(current.groupName) + 1 } ?: 0
                        LiveGroupRow(
                            name = name,
                            count = if (index == 0) channels.size else channels.count { it.groupName == name },
                            selected = groupIndex == index,
                            playing = playingGroupIndex == index,
                            focusModifier = if (index == groupIndex) Modifier.focusRequester(groupFocusRequester) else Modifier,
                            onClick = { actions.enterLiveGroup(index) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = channelListState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(visibleChannels, key = { _, channel -> channel.number }) { indexInVisible, channel ->
                        val globalIndex = channels.indexOfFirst { it.number == channel.number }
                        LiveChannelRow(
                            channel = channel,
                            selected = globalIndex == safeSelectedIndex,
                            focusModifier = if (indexInVisible == focusTargetIndex) {
                                Modifier.focusRequester(channelFocusRequester)
                            } else {
                                Modifier
                            },
                            onClick = { onChannelPicked(globalIndex) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveGroupRow(
    name: String,
    count: Int,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = Color(0xFF7AE2BA)
    val background = when { selected -> accent.copy(alpha = 0.12f); focused -> Color.White.copy(alpha = 0.1f); else -> Color.Transparent }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = focusModifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, if (focused || selected) accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            color = Color.White.copy(alpha = if (selected || focused) 1f else 0.82f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (playing) {
            Text("播放中", color = accent, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = "$count",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(" ›", color = Color.White.copy(alpha = 0.35f), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun LiveChannelRow(
    channel: LiveChannel,
    selected: Boolean,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = Color(0xFF7AE2BA)
    val background = when { selected -> accent.copy(alpha = 0.1f); focused -> Color.White.copy(alpha = 0.1f); else -> Color.Transparent }
    val contentColor = Color.White.copy(alpha = if (selected || focused) 1f else 0.78f)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = focusModifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, if (focused) accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.number.toString(),
            modifier = Modifier.width(34.dp),
            color = if (selected) accent else Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = channel.name,
            modifier = Modifier.weight(1f),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MobileLiveControls(
    isPortrait: Boolean,
    channelName: String,
    lineLabel: String,
    orientationActionLabel: String,
    onPrevious: () -> Unit,
    onChannels: () -> Unit,
    onNext: () -> Unit,
    onNextLine: () -> Unit,
    onRefresh: () -> Unit,
    onFullscreen: () -> Unit,
    fullscreenActionLabel: String,
    onOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).background(Color(0xFF7AE2BA), CircleShape))
            Text(channelName, Modifier.weight(1f), color = Color.White,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isPortrait) Text(lineLabel, color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        if (isPortrait) {
            Text(lineLabel, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobileLiveControlButton("上一台", onPrevious, expand = true)
                MobileLiveControlButton("频道", onChannels, expand = true, primary = true)
                MobileLiveControlButton("下一台", onNext, expand = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobileLiveControlButton("换线", onNextLine, expand = true)
                MobileLiveControlButton("刷新", onRefresh, expand = true)
                MobileLiveControlButton(fullscreenActionLabel, onFullscreen, expand = true)
                MobileLiveControlButton(orientationActionLabel, onOrientation, expand = true)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MobileLiveControlButton("上一台", onPrevious)
                MobileLiveControlButton("频道", onChannels, primary = true)
                MobileLiveControlButton("下一台", onNext)
                Spacer(Modifier.weight(1f))
                MobileLiveControlButton("换线", onNextLine)
                MobileLiveControlButton("刷新", onRefresh)
                MobileLiveControlButton(fullscreenActionLabel, onFullscreen)
                MobileLiveControlButton(orientationActionLabel, onOrientation)
            }
        }
    }
}

@Composable
private fun RowScope.MobileLiveControlButton(
    label: String,
    onClick: () -> Unit,
    expand: Boolean = false,
    primary: Boolean = false,
) {
    ControlButton(label, onClick, if (expand) Modifier.weight(1f) else Modifier, primary = primary)
}

@Composable
private fun LivePrompt(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xB8000000))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val CHANNEL_LIST_HIDE_DELAY_MS = 6_000L
private const val LIVE_CONNECT_TIMEOUT_MS = 8_000
private const val LIVE_READ_TIMEOUT_MS = 12_000
private const val MOBILE_CONTROLS_HIDE_DELAY_MS = 4_000L
private const val CHANNEL_BADGE_HIDE_DELAY_MS = 2_000L
private const val CHANNEL_NUMBER_COMMIT_DELAY_MS = 1_000L
private const val PROMPT_HIDE_DELAY_MS = 1_500L
private const val MAX_CHANNEL_NUMBER_DIGITS = 4
private const val LIVE_CONTINUOUS_BUFFER_THRESHOLD_MS = 6_000L
private const val LIVE_FREQUENT_BUFFER_WINDOW_MS = 60_000L
private const val LIVE_FREQUENT_BUFFER_COUNT = 3
private const val LIVE_CUMULATIVE_BUFFER_THRESHOLD_MS = 12_000L
private const val LIVE_PROGRESS_SAMPLE_INTERVAL_MS = 1_000L
private const val MOBILE_PORTRAIT_CHANNEL_PANEL_HEIGHT_FRACTION = 0.62f
private const val MOBILE_LANDSCAPE_CHANNEL_PANEL_WIDTH_FRACTION = 0.44f
private const val LIVE_LOG_TAG = "TVBoxLive"
