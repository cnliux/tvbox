package com.tvbox.app.ui

import android.content.pm.ActivityInfo
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tvbox.app.data.liveFavoriteKey
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.LiveChannelNumberMatch
import com.tvbox.app.domain.LiveLineFallbackReason
import com.tvbox.app.domain.LivePlaybackWatchdog
import com.tvbox.app.domain.PlaybackBufferDecision
import com.tvbox.app.domain.PlaybackBufferMonitor
import com.tvbox.app.domain.findLiveChannelsByNumberPrefix
import com.tvbox.app.domain.probeLiveLineMimeType
import com.tvbox.app.domain.resolveLiveChannelNumberTarget
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.util.logSafeUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 电视直播页（电视专用）：全屏播放 + 半透明浮层菜单。
 *
 * 手机端直播交互已迁移到独立的 MobileLiveScreen；本文件只保留遥控器交互：
 * 默认全屏播放，顶/底信息条 3 秒无操作自动淡出，MENU/确认唤出左侧半透明频道浮层，
 * 右下角 EPG 窄卡常驻，数字键直选频道。
 */
@Composable
fun LiveScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val session = rememberPlaybackSession()
    BindPlaybackSession(session, null)
    // 兼容委托 getter 不支持智能转换，先取局部快照再判空。
    val liveError = state.liveError
    PageSurface { padding ->
        when {
            // 加载态用红色进度环（与直播页强调色一致），文案按"正在加载 <频道/直播源>"。
            state.liveLoading -> LiveLoadingState(
                text = "正在加载直播源...",
                modifier = Modifier.padding(padding),
            )
            liveError != null -> ErrorState(
                message = liveError,
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
    // 画面比例：false = 原始比例（FIT，默认），true = 铺满裁切（ZOOM）。底部「⛶」按钮切换。
    var liveFillScreen by remember { mutableStateOf(false) }
    // 缓冲标记必须走 Compose 状态：直接读 player.playbackState 不会触发重组，
    // 加载环会一直挂在画面上。由播放器回调写入。
    var playerBuffering by remember { mutableStateOf(false) }
    // 线路兜底全部用尽后的兜底提示：避免黑屏时加载环无限旋转、用户没有可操作的出口。
    var streamBlocked by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val isTelevision = state.effectiveDeviceMode == DeviceMode.Television
    val initialRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
    val player = rememberManagedExoPlayer()
    val playerFocusRequester = remember { FocusRequester() }
    val blockedRetryFocus = remember { FocusRequester() }
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
    // 浮层内遥控焦点移动时的"预览频道"：只驱动 EPG 卡，不改变播放目标（确认键才切台）。
    var previewChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // 顶/底信息条与频道 badge 共用一套显隐：任意按键/触摸恢复并重置 3 秒计时，超时整体淡出。
    var barsVisible by remember { mutableStateOf(true) }
    var barsNonce by remember { mutableIntStateOf(0) }
    var channelNumberInput by remember { mutableStateOf("") }
    var channelNumberNonce by remember { mutableIntStateOf(0) }
    var promptMessage by remember { mutableStateOf("") }
    var promptNonce by remember { mutableIntStateOf(0) }
    var promptVisible by remember { mutableStateOf(false) }
    var bufferingLineUrl by remember { mutableStateOf<String?>(null) }
    var bufferingCheckNonce by remember { mutableIntStateOf(0) }
    var automaticSwitchLineUrl by remember { mutableStateOf<String?>(null) }
    // 顶栏时钟与 EPG 卡"当前节目/进度"共用 20 秒节拍，保证分钟级刷新。
    var clockTick by remember { mutableIntStateOf(0) }
    val clockText = remember(clockTick) { formatClock(System.currentTimeMillis()) }
    // 播放器回调注册在 DisposableEffect(player) 里，只会捕获首次组合时的频道快照；
    // 换台后日志里的频道名/线路号会停留在旧值，排查时极易误判。改用更新态读取。
    val latestChannelLabel = rememberUpdatedState("${currentChannel.groupName}/${currentChannel.name}")
    val latestLineIndex = rememberUpdatedState(state.liveLineIndex)
    val latestLineCount = rememberUpdatedState(currentChannel.lines.size)

    fun bumpBars() {
        barsVisible = true
        barsNonce++
    }

    fun showPrompt(message: String) {
        promptMessage = message
        promptVisible = true
        promptNonce++
    }

    fun showChannelList() {
        channelListVisible = true
        bumpBars()
        channelListInteraction++
    }

    fun keepChannelListVisibleWhileBrowsing() {
        if (channelListVisible) channelListInteraction++
    }

    fun handleNumberKey(number: Int): Boolean {
        channelNumberInput = (channelNumberInput + number.toString()).takeLast(MAX_CHANNEL_NUMBER_DIGITS)
        bumpBars()
        channelNumberNonce++
        return true
    }

    // 数字输入期间的即时匹配：输入 "0" 就列出所有 0 开头的频道，确认键直接进台。
    val numberMatches: List<LiveChannelNumberMatch> = remember(
        channels,
        channelNumberInput,
        state.appSettings.liveNumberSearchLimit,
    ) {
        findLiveChannelsByNumberPrefix(
            channels,
            channelNumberInput,
            limit = state.appSettings.liveNumberSearchLimit,
        )
    }
    val visibleNumberMatches = numberMatches.take(state.appSettings.liveNumberMatchRows)
    var numberMatchFocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(channelNumberInput, visibleNumberMatches.size) { numberMatchFocus = 0 }

    fun commitNumberInput(useFocusedMatch: Boolean) {
        val input = channelNumberInput
        if (input.isEmpty()) return
        channelNumberInput = ""
        numberMatchFocus = 0
        val target: Int? = if (useFocusedMatch) {
            visibleNumberMatches.getOrNull(numberMatchFocus)?.index
        } else {
            resolveLiveChannelNumberTarget(channels, input).takeIf { it >= 0 }
        }
        if (target == null) {
            showPrompt("无频道内容")
            return
        }
        actions.selectLiveChannel(target)
        bumpBars()
        channels.getOrNull(target)?.let { showPrompt(it.name) }
    }

    fun switchLiveLineAfterIssue(reason: LiveLineFallbackReason) {
        val lineUrl = latestLineUrl.value
        android.util.Log.w(
            LIVE_LOG_TAG,
            "【触发线路容错】reason=$reason channel=${latestChannelLabel.value}, currentLineIndex=${latestLineIndex.value}, totalLines=${latestLineCount.value}, currentUrl=${logSafeUrl(lineUrl)}, canPlay=${session.canPlay}"
        )
        if (!session.canPlay) {
            android.util.Log.w(LIVE_LOG_TAG, "【容错跳过】session.canPlay=false")
            return
        }
        if (latestLineCount.value <= 1 && reason == LiveLineFallbackReason.Error) {
            // 当前频道只有一条线路且播放器硬错误：直接换台，避免长时间黑屏。
            android.util.Log.w(LIVE_LOG_TAG, "【自动切换】单线频道硬错误，切换到下一台")
            actions.playNextLiveChannel()
            showPrompt("当前频道无法播放，已自动切换到下一台")
            return
        }
        val result = actions.advanceLiveLineAfterFailure(reason)
        android.util.Log.i(LIVE_LOG_TAG, "【自动容错】advanceLiveLineAfterFailure($reason) result=$result")
        if (!result) {
            // 所有线路都试过仍无可用流：停止无限缓冲提示，交给用户选择重试或换台。
            streamBlocked = true
        }
    }

    fun handleBufferDecision(decision: PlaybackBufferDecision?) {
        if (decision != null) {
            switchLiveLineAfterIssue(LiveLineFallbackReason.Stall)
        }
    }

    BackHandler {
        android.util.Log.d(
            LIVE_LOG_TAG,
            "【返回键】页面级返回, channelListVisible=$channelListVisible, showLineControl=$showLineControl",
        )
        // 返回键按浮层层级逐级收起：线路浮层 → 频道浮层 → 退出直播页。
        // 只注册一个处理器，避免多个 BackHandler 的启用顺序导致一次返回只收起其中一层。
        when {
            showLineControl -> {
                android.util.Log.d(LIVE_LOG_TAG, "【返回键】收起线路浮层")
                showLineControl = false
                if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
            }
            channelListVisible -> {
                android.util.Log.d(LIVE_LOG_TAG, "【返回键】收起频道浮层")
                channelListVisible = false
                if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
            }
            else -> actions.goBack()
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
                    "【播放器错误】code=${error.errorCode}, name=${error.errorCodeName}, msg=${error.message}, cause=${error.cause?.message}, channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, url=${logSafeUrl(latestLineUrl.value)}",
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

            override fun onRenderedFirstFrame() {
                // 与 STATE_READY 区分：READY 只代表解码器可接收数据，出帧才是真正有画面。
                android.util.Log.i(
                    LIVE_LOG_TAG,
                    "【首帧渲染】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}"
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                android.util.Log.i(
                    LIVE_LOG_TAG,
                    "【视频尺寸】${videoSize.width}x${videoSize.height}, pixelRatio=${videoSize.pixelWidthHeightRatio}"
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 加载环走 Compose 状态：直接读 player.playbackState 不会触发重组。
                playerBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) streamBlocked = false
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                android.util.Log.d(
                    LIVE_LOG_TAG,
                    "【播放状态】$stateName, playWhenReady=${player.playWhenReady}, position=${player.currentPosition}, buffered=${player.bufferedPercentage}%, url=${logSafeUrl(latestLineUrl.value)}"
                )
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        android.util.Log.w(LIVE_LOG_TAG, "【开始缓冲】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, url=${logSafeUrl(latestLineUrl.value)}")
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
                        android.util.Log.i(LIVE_LOG_TAG, "【播放就绪】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, buffered=${player.bufferedPercentage}%, url=${logSafeUrl(latestLineUrl.value)}")
                        bufferingLineUrl = null
                        // 画面真正就绪后清零卡顿重试计数，避免跨频道累计导致提前换线。
                        actions.notifyLivePlaybackReady()
                        handleBufferDecision(
                            bufferMonitor.onReady(
                                playWhenReady = player.playWhenReady,
                                nowMs = System.currentTimeMillis(),
                            ).decision,
                        )
                    }
                    Player.STATE_ENDED -> {
                        android.util.Log.w(LIVE_LOG_TAG, "【播放结束】channel=${latestChannelLabel.value}, lineIndex=${latestLineIndex.value}, url=${logSafeUrl(latestLineUrl.value)}")
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
        }
    }

    LaunchedEffect(
        state.live.channelIndex,
        state.liveLineIndex,
        currentLine.url,
        state.liveReconnectNonce,
    ) {
        // 频道/线路身份也必须参与 key：不同频道或不同线路可能共用同一个 URL
        // （同一地址配不同 Referer），只按 URL 比较会漏掉重新 setMediaSource，表现为换台后黑屏。
        android.util.Log.d(
            LIVE_LOG_TAG,
            "【切换线路】channel=${currentChannel.name}, group=${currentChannel.groupName}, lineIndex=${state.liveLineIndex}, protocol=${currentLine.protocolHint}, headers=${currentLine.headers.keys}, nonce=${state.liveReconnectNonce}"
        )
        streamBlocked = false
        bufferMonitor.onMediaChanged()
        playbackWatchdog.onMediaChanged()
        bufferingLineUrl = null
        automaticSwitchLineUrl = null
        // 换台/换线即一次"操作"：信息条与频道 badge 恢复显示并重新计时。
        bumpBars()
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
        // 换台后 badge 与信息条同步显示，3 秒后由 barsNonce 计时统一淡出。
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

    val liveLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(player, currentLine.url, liveLifecycleOwner) {
        // 退到后台后组合仍存活：不停止采样会持续 1 次/秒唤醒主线程并刷新 verbose 日志。
        liveLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 停止采样期间没有新的进度点，恢复采样前先清空看门狗，
            // 避免把后台暂停的这段时间误判成卡死。
            playbackWatchdog.onPaused()
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
    }

    LaunchedEffect(Unit) {
        runCatching { playerFocusRequester.requestFocus() }
    }

    // 线路兜底用尽后把遥控焦点移到「重试」，恢复后交还播放器。
    LaunchedEffect(streamBlocked) {
        if (!isTelevision) return@LaunchedEffect
        runCatching {
            if (streamBlocked) blockedRetryFocus.requestFocus()
            else playerFocusRequester.requestFocus()
        }
    }

    // ViewModel 侧的容错/订阅切换结果通过 liveNotice 下发，这里统一转成轻提示并消费。
    LaunchedEffect(state.liveNotice) {
        val notice = state.liveNotice ?: return@LaunchedEffect
        showPrompt(notice)
        actions.consumeLiveNotice()
    }

    // 浮层打开时 6 秒无操作自动收起（由浮层自己的计时负责，不与信息条 3 秒计时互相打断）。
    LaunchedEffect(channelListVisible, channelListInteraction) {
        if (!channelListVisible) return@LaunchedEffect
        delay(CHANNEL_LIST_HIDE_DELAY_MS)
        channelListVisible = false
        if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
    }

    // 顶/底信息条 3 秒无操作淡出；换台（取流 effect bumpBars）同样重新计时。
    LaunchedEffect(barsNonce, barsVisible) {
        if (!barsVisible) return@LaunchedEffect
        delay(LIVE_BARS_HIDE_DELAY_MS)
        barsVisible = false
    }

    LaunchedEffect(liveLifecycleOwner) {
        // 退到后台后不再每 20 秒唤醒刷新时钟与节目单；恢复时先补一次 tick，
        // 否则会拿后台前的旧时间戳显示时间、可能高亮已结束的节目。
        liveLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            clockTick++
            while (true) {
                delay(CLOCK_REFRESH_INTERVAL_MS)
                clockTick++
            }
        }
    }

    LaunchedEffect(channelNumberNonce) {
        if (channelNumberNonce == 0) return@LaunchedEffect
        delay(CHANNEL_NUMBER_COMMIT_DELAY_MS)
        // 已被确认键提前提交时输入为空，此时不应再提示一次"无频道内容"。
        if (channelNumberInput.isEmpty()) return@LaunchedEffect
        promptVisible = false
        commitNumberInput(useFocusedMatch = false)
    }

    PromptTimer(nonce = promptNonce, hideDelayMs = PROMPT_HIDE_DELAY_MS) { promptVisible = false }

    val barsAlpha by animateFloatAsState(
        targetValue = if (barsVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "live-bars-alpha",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .pointerInput(channels.size) {
                detectTapGestures(
                    onTap = { bumpBars() },
                    onDoubleTap = { tapOffset ->
                        if (tapOffset.x < size.width / 2f) {
                            actions.playPreviousLiveChannel()
                            showPrompt("上一个频道")
                        } else {
                            actions.playNextLiveChannel()
                            showPrompt("下一个频道")
                        }
                        bumpBars()
                    },
                )
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                // 规范：全屏态下任意按键唤出顶部/底部信息条（并重置 3 秒淡出计时）。
                // 放在最前面统一处理，各分支里重复的 bumpBars 仅作为"操作生效"的冗余保险。
                bumpBars()
                // 浮层打开时方向键/确认键交给浮层自身的焦点系统，只顺手重置自动隐藏计时；
                // 若在根节点把 D-PAD 吞掉换台，分组/频道列表将无法用遥控器操作。
                if (channelListVisible) {
                    // 浮层打开时数字键仍可直选：先收起浮层，再走统一的数字输入计时跳转。
                    val overlayDigit = liveNumberKeyOf(event.nativeKeyEvent.keyCode)
                    if (overlayDigit != null) {
                        channelListVisible = false
                        if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
                        handleNumberKey(overlayDigit)
                        return@onPreviewKeyEvent true
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_MENU,
                        AndroidKeyEvent.KEYCODE_INFO,
                        -> {
                            channelListVisible = false
                            if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
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
                            bumpBars()
                            false
                        }
                        else -> false
                    }
                } else if (showLineControl) {
                    // 线路浮层：方向/确认键放行给浮层焦点系统；MENU 收起浮层。
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_MENU,
                        AndroidKeyEvent.KEYCODE_INFO,
                        -> {
                            showLineControl = false
                            if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
                            true
                        }
                        else -> false
                    }
                } else if (channelNumberInput.isNotEmpty()) {
                    // 数字输入中：方向键在候选里移动，确认键直接进台，退格删一位。
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (visibleNumberMatches.isNotEmpty()) {
                                numberMatchFocus = (numberMatchFocus - 1).coerceAtLeast(0)
                            }
                            bumpBars()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (visibleNumberMatches.isNotEmpty()) {
                                numberMatchFocus = (numberMatchFocus + 1).coerceAtMost(visibleNumberMatches.lastIndex)
                            }
                            bumpBars()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER,
                        -> {
                            commitNumberInput(useFocusedMatch = visibleNumberMatches.isNotEmpty())
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DEL -> {
                            channelNumberInput = channelNumberInput.dropLast(1)
                            channelNumberNonce++
                            bumpBars()
                            true
                        }
                        else -> false
                    }
                } else when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        actions.playPreviousLiveChannel()
                        bumpBars()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        actions.playNextLiveChannel()
                        bumpBars()
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
                            // 确认键唤出频道浮层（底部「线路」按钮负责打开线路弹层）。
                            showChannelList()
                        }
                        bumpBars()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MENU,
                    AndroidKeyEvent.KEYCODE_INFO,
                    -> {
                        showChannelList()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        actions.playPreviousLiveLine()
                        bumpBars()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        actions.playNextLiveLine()
                        bumpBars()
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
                it.resizeMode = if (liveFillScreen) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 缓冲/加载中：红色进度环 + 「正在加载 <频道名>...」，缓冲结束自动消失。
        if (playerBuffering && !session.interrupted && !streamBlocked) {
            LiveLoadingState(
                text = "正在加载 ${currentChannel.name}...",
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (streamBlocked && !session.interrupted) {
            // 线路兜底用尽：不再让加载环无限转，给出可操作的出口。
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "当前频道所有线路均不可用",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "可重试当前线路，或切换到下一台",
                    color = Color.White.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlButton(
                        "重试",
                        {
                            streamBlocked = false
                            actions.reconnectLiveLine()
                        },
                        Modifier.focusRequester(blockedRetryFocus),
                        primary = true,
                    )
                    ControlButton(
                        "下一台",
                        {
                            streamBlocked = false
                            actions.playNextLiveChannel()
                        },
                    )
                }
            }
        }
        if (session.interrupted) {
            ControlButton("继续播放", {
                player.seekToDefaultPosition()
                session.play(player)
            }, Modifier.align(Alignment.Center), primary = true)
        }
        // 频道 badge 与信息条同步淡出（换台时由取流 effect bumpBars 重新点亮）。
        if (!channelListVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .graphicsLayer { alpha = barsAlpha }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 20.dp, bottom = 8.dp),
            ) {
                LiveChannelBadge(
                    channel = currentChannel,
                    lineIndex = state.liveLineIndex,
                    lineCount = currentChannel.lines.size,
                    channelCount = channels.size,
                )
            }
        }
        // ── 顶部导航条（52dp，黑 0.75→透明渐变，随 barsVisible 淡出）──────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer { alpha = barsAlpha },
        ) {
            LiveTopBar(
                favorite = actions.isLiveFavorite(currentChannel),
                clockText = clockText,
                onPlatformLive = {
                    bumpBars()
                    actions.openPlatformLive()
                },
                onToggleFavorite = {
                    actions.toggleLiveFavorite(currentChannel)
                    bumpBars()
                },
            )
        }
        // ── 底部状态条（56dp，透明→黑 0.88 渐变，随 barsVisible 淡出）─────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer { alpha = barsAlpha },
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(7.dp).background(LiveTvAccent, CircleShape))
                Text(
                    text = "直播中",
                    color = LiveTvAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = currentChannel.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "线路 ${state.liveLineIndex + 1}/${currentChannel.lines.size}",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
                if (currentLine.protocolHint.isNotBlank()) {
                    Text(
                        text = currentLine.protocolHint.uppercase(),
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                LiveBottomBarButton(
                    text = if (actions.isLiveFavorite(currentChannel)) "♥ 已收藏" else "♥ 收藏",
                    onClick = {
                        actions.toggleLiveFavorite(currentChannel)
                        bumpBars()
                    },
                )
                LiveBottomBarButton(
                    text = "⇄ 线路 ${state.liveLineIndex + 1}/${currentChannel.lines.size}",
                    onClick = {
                        showLineControl = true
                        bumpBars()
                    },
                )
                LiveBottomBarButton(
                    text = if (session.interrupted) "⛶ 继续播放" else if (liveFillScreen) "⛶ 原始比例" else "⛶ 全屏",
                    onClick = {
                        if (session.interrupted) {
                            player.seekToDefaultPosition()
                            session.play(player)
                        } else {
                            // 「⛶」= 画面比例在"原始(FIT)"与"铺满裁切(ZOOM)"间切换。
                            // 真实 URL 调试入口保留在线路浮层的「查看 URL」。
                            liveFillScreen = !liveFillScreen
                            showPrompt(if (liveFillScreen) "画面已铺满" else "已恢复原始比例")
                        }
                        bumpBars()
                    },
                )
            }
        }
        // ── 右下 EPG 卡（485dp，web 调参）──────────────────────────────────────
        // 规格：电视端节目单只在按确认键唤出浮层后才显示，全屏态不常驻，
        // 保证画面主体干净。浮层打开时卡片贴浮层右缘（267dp + 间距）。
        if (channelListVisible) {
            // 浮层浏览期间 EPG 卡跟随遥控焦点（预览），未移动焦点时显示正在播放的频道。
            val epgChannel = previewChannel ?: currentChannel
            LiveEpgCard(
                channel = epgChannel,
                lineIndex = if (previewChannel == null) state.liveLineIndex else 0,
                nowProgram = actions.liveEpgNow(epgChannel),
                programs = remember(
                    epgChannel,
                    clockTick,
                    state.liveEpgByChannel,
                    state.appSettings.epgCardRows,
                ) {
                    buildLiveEpgDisplay(
                        actions.liveEpgPrograms(epgChannel),
                        rows = state.appSettings.epgCardRows,
                    )
                },
                nowMs = remember(clockTick) { System.currentTimeMillis() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 248.dp, bottom = 75.dp),
            )
        }
        if (channelListVisible) {
            LiveChannelOverlayPanel(
                state = state,
                actions = actions,
                onClose = {
                    channelListVisible = false
                    previewChannel = null
                    bumpBars()
                    if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
                },
                onChannelPicked = { index ->
                    actions.selectLiveChannel(index)
                    channelListVisible = false
                    previewChannel = null
                    bumpBars()
                    showPrompt(channels[index].name)
                    if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
                },
                onInteraction = ::keepChannelListVisibleWhileBrowsing,
                onPreview = { previewChannel = it },
            )
        }
        // ── 数字直选：中央大浮层，输入中常驻，2 秒后自动跳转 ───────────────────
        // 输入期间实时列出前缀匹配的频道（输入 "0" → 所有 0 开头频道），
        // 确认键跳到当前高亮项，继续输入可缩小范围。
        if (channelNumberInput.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xB8000000))
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = channelNumberInput,
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (numberMatches.isEmpty()) "无匹配频道" else "频道号",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
                if (visibleNumberMatches.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    visibleNumberMatches.forEachIndexed { rowIndex, match ->
                        val highlighted = rowIndex == numberMatchFocus.coerceIn(0, visibleNumberMatches.lastIndex)
                        Row(
                            modifier = Modifier
                                .width(260.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (highlighted) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = match.number.toString(),
                                color = if (highlighted) Color.White else Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(40.dp),
                            )
                            Text(
                                text = match.channel.name,
                                color = if (highlighted) Color.White else Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp,
                                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (promptVisible && promptMessage.isNotBlank()) {
            LivePrompt(
                message = promptMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 80.dp),
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
                        color = LiveTvAccent,
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
            LiveLineOverlay(
                state = state,
                actions = actions,
                channel = currentChannel,
                onClose = {
                    showLineControl = false
                    if (isTelevision) runCatching { playerFocusRequester.requestFocus() }
                },
                onCopyUrl = {
                    val clip = android.content.ClipData.newPlainText("live_url", currentLine.url)
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                    showPrompt("URL 已复制到剪贴板")
                },
                onViewUrl = {
                    showUrlOverlay = true
                    showLineControl = false
                },
            )
        }
    }
}

