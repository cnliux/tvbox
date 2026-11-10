package com.tvbox.app.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tvbox.app.data.DlnaCaster
import com.tvbox.app.data.DlnaDevice
import com.tvbox.app.domain.PlaybackAgentDecision
import com.tvbox.app.domain.appendPlaybackErrorHint
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.PlaybackAttemptTracker
import com.tvbox.app.domain.correspondingEpisodeIndex
import com.tvbox.app.domain.PlaybackBufferDecision
import com.tvbox.app.domain.PlaybackBufferMonitor
import com.tvbox.app.domain.PlaybackIssueType
import com.tvbox.app.domain.PlaybackIssueNotice
import com.tvbox.app.domain.PlaybackResumeAnchor
import com.tvbox.app.domain.PlaybackFailureKind
import com.tvbox.app.domain.PlaybackWatchdog
import com.tvbox.app.domain.resolveVideoScaleMode
import com.tvbox.app.domain.vodVideoScaleKey
import com.tvbox.app.domain.SlowBufferReason
import com.tvbox.app.ui.components.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    KeepScreenOnWhileVisible()
    val movie = state.detailMovie
    val source = movie?.playSources?.getOrNull(state.playerSourceIndex)
    val episode = source?.episodes?.getOrNull(state.playerEpisodeIndex)


    if (movie == null || source == null || episode == null) {
        // 这里没有可重试的请求：地址本身不存在，重试只会拿同一条数据再走一遍。
        // 与详情页「详情为空」保持一致，用空态给出明确的返回出口，避免出现写着「重试」实际却返回的按钮。
        EmptyState(
            title = "播放地址不存在",
            subtitle = "请返回详情页重新选择线路或集数",
            actionLabel = "返回详情",
            onAction = actions::goBack,
        )
        return
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    FullscreenWhilePlayingPageVisible(activity)
    val isTelevision = state.effectiveDeviceMode == DeviceMode.Television
    val isMobile = state.effectiveDeviceMode == DeviceMode.Mobile
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    // 长按临时倍速必须跨重组保存：提示文案与 250ms 进度快照都会触发重组，
    // 普通局部变量每次重组都被重新初始化成 1f，导致松手后原倍速丢失。
    // 声明放在 Player.Listener 之前，监听器才能读到 active 标记、避免把瞬时 2x 写入偏好。
    var longPressOriginalSpeed by remember { mutableFloatStateOf(1f) }
    var longPressSpeedActive by remember { mutableStateOf(false) }
    val initialScreenBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    val initialRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val playbackHost = rememberPlaybackHost(state.appSettings.decoderStrategy)
    val player = playbackHost.player
    // 音轨/字幕随播放器实例读取；重建后由新的 player 重新挂监听。
    val playerTracks = rememberPlayerTracks(player)
    val session = rememberPlaybackSession()
    // 画面比例按"影片 + 集"记忆；未记忆时回退设置里的全局默认值。
    val videoScaleContentKey = vodVideoScaleKey(
        apiLineId = movie.apiLineId,
        movieId = movie.id.toString(),
        episodeIndex = player.currentMediaItemIndex.coerceAtLeast(0),
    )
    val videoScaleMode = state.appSettings.resolveVideoScaleMode(videoScaleContentKey)
    var loadedState by remember { mutableStateOf(state) }
    val resumeAnchor = remember { PlaybackResumeAnchor() }
    // 解码策略变化会重建播放器：本页记住重建前的集与进度，重建后原处续播。
    // savePlaybackProgress 只写历史、不回写 state.playerStartPositionMs，不能从 state 恢复。
    val rebuildSeed = remember { PlaybackRebuildSeed() }
    DisposableEffect(player) {
        onDispose { rebuildSeed.remember(player) }
    }
    fun isCurrentPlayback(): Boolean = player.currentMediaItem?.mediaId?.let {
        it == actions.state.value.currentPlaybackKey()
    } == true
    fun saveCurrentProgress(positionMs: Long, durationMs: Long) {
        val position = if (player.playbackState == Player.STATE_READY) positionMs
            else resumeAnchor.positionForFailure(positionMs)
        actions.savePlaybackProgress(position, durationMs,
            loadedState.copy(playerEpisodeIndex = player.currentMediaItemIndex.coerceAtLeast(0)))
    }
    BindPlaybackSession(session, player) {
        saveCurrentProgress(player.currentPosition, player.duration.takeIf { it > 0 } ?: 0)
    }
    // 强制硬解无可用硬解并回落时，四个播放页共用同一段一次性提示。
    HardwareDecoderFallbackToast()
    // 控件状态收敛到共享层：visible / panel / 自动隐藏 / 焦点恢复 / 锁屏由 PlayerChromeState 统一管理。
    val chrome = remember(movie.id, movie.apiLineId) { PlayerChromeState() }
    val playerFocusRequester = chrome.rootFocus
    // 播放信息只在面板打开时采样，关闭即停；采集器本身不改变播放行为。
    val diagnostics = rememberPlayerDiagnostics(player, chrome.panel == PlayerPanel.Info)
    PlayerChromeBackHandler(
        state = chrome,
        onExit = {
            // 控件已隐藏时单次返回直接退出；退出前保存进度，避免少一次确认后漏存。
            if (isMobile) {
                saveCurrentProgress(player.currentPosition, player.duration.takeIf { it > 0 } ?: 0)
            }
            actions.goBack()
        },
        onLockedHint = {
            android.widget.Toast.makeText(
                context,
                "播放已锁定，点“解锁”恢复",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        },
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiPosition = playbackHost.snapshot.positionMs
    val uiDuration = playbackHost.snapshot.durationMs
    val uiPlaying = playbackHost.snapshot.playWhenReady
    LaunchedEffect(player, lifecycleOwner) {
        // 进度/时长/播放意图由 rememberPlaybackHost 轮询；这里只维护续播锚点。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (isCurrentPlayback() && player.playbackState == Player.STATE_READY) {
                    resumeAnchor.update(player.currentPosition)
                }
                delay(250)
            }
        }
    }
    PlayerChromeAutoHide(chrome, uiPlaying, autoHideMs = PLAYER_CHROME_AUTO_HIDE_MS)
    LaunchedEffect(session.interrupted) {
        if (session.interrupted) chrome.bump()
    }
    LaunchedEffect(chrome.visible) {
        if (!chrome.visible) {
            androidx.compose.runtime.withFrameNanos { }
            // 控件隐藏时目标节点可能尚未进入组合，requestFocus 需容错（与项目其余调用一致）。
            runCatching { playerFocusRequester.requestFocus() }
        }
    }
    var videoDisplayMode by remember { mutableStateOf(VideoDisplayMode.Unknown) }
    var playbackIssue by remember { mutableStateOf<PlaybackIssueNotice?>(null) }
    var seekGesturePrompt by remember { mutableStateOf<String?>(null) }
    var seekGesturePromptNonce by remember { mutableIntStateOf(0) }
    var playbackNoticeNonce by remember { mutableIntStateOf(0) }
    var playbackNotice by remember { mutableStateOf<String?>(null) }
    val bufferMonitor = remember { PlaybackBufferMonitor() }
    val attemptTracker = remember { PlaybackAttemptTracker() }
    // 共享看门狗：从建源起算首帧，出帧后才开始判卡死。
    val playbackWatchdog = remember { PlaybackWatchdog() }
    var firstFrameRendered by remember(movie.apiLineId, movie.id) { mutableStateOf(false) }
    var firstFrameFailure by remember(movie.apiLineId, movie.id) {
        mutableStateOf<PlaybackFailureKind?>(null)
    }
    val overlayRetryFocus = remember { FocusRequester() }
    var bufferingPlaybackKey by remember { mutableStateOf<String?>(null) }
    var bufferingCheckNonce by remember { mutableIntStateOf(0) }
    var failedSourceIndexes by remember(movie.apiLineId, movie.id) {
        mutableStateOf(emptySet<Int>())
    }
    /** 新一集开始播放后等待 READY，再统一跳过片头。 */
    var introSkipPending by remember(movie.apiLineId, movie.id) { mutableStateOf(false) }
    /** 已触发过片尾跳过的那一集，避免重复触发。 */
    var outroSkipHandledKey by remember(movie.apiLineId, movie.id) { mutableStateOf<String?>(null) }
    val latestState by rememberUpdatedState(state)

    // 片尾跳过：剩 N 秒时自动进入下一集（每集只触发一次）。
    LaunchedEffect(player, lifecycleOwner) {
        // 片尾检测同样只在可见时轮询：后台时播放已被会话暂停，
        // 继续 4 次/秒唤醒主线程既无意义又会干扰系统省电策略。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(250L)
                if (!isCurrentPlayback()) continue
                val outroSkipMs = actions.state.value.appSettings.outroSkipSeconds.toLong() * 1_000L
                if (outroSkipMs <= 0) continue
                val duration = player.duration
                if (duration <= 0 || player.playbackState != Player.STATE_READY) continue
                val remaining = duration - player.currentPosition
                val playbackKey = actions.state.value
                    .copy(playerEpisodeIndex = player.currentMediaItemIndex)
                    .currentPlaybackKey()
                if (remaining > outroSkipMs) {
                    // 回看或拖动离开片尾后，允许再次触发。
                    if (outroSkipHandledKey == playbackKey) outroSkipHandledKey = null
                    continue
                }
                val nextIndex = player.currentMediaItemIndex + 1
                if (
                    remaining > 0 && player.isPlaying && playbackKey != null &&
                    outroSkipHandledKey != playbackKey && nextIndex < player.mediaItemCount
                ) {
                    outroSkipHandledKey = playbackKey
                    saveCurrentProgress(player.currentPosition, duration)
                    player.seekTo(nextIndex, 0L)
                    session.play(player)
                }
            }
        }
    }

    val canCast = !isTelevision
    val dlnaCaster = remember(context) { DlnaCaster(context) }
    val castScope = rememberCoroutineScope()
    // 投屏停止需要在页面销毁时发出，使用页面持有的作用域，避免随 rememberCoroutineScope 一起被取消。
    val castStopScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var castDialogVisible by remember { mutableStateOf(false) }
    var castDevices by remember { mutableStateOf<List<DlnaDevice>>(emptyList()) }
    var castDiscovering by remember { mutableStateOf(false) }
    var castError by remember { mutableStateOf<String?>(null) }
    var castSearchNonce by remember { mutableIntStateOf(0) }
    var castingDevice by remember { mutableStateOf<DlnaDevice?>(null) }
    val stopCasting = {
        castingDevice?.let { device -> dlnaCaster.stopAsync(device, castStopScope) }
        if (castingDevice != null) {
            castingDevice = null
            seekGesturePrompt = "已停止投屏"
            seekGesturePromptNonce++
        }
    }
    val startCasting: (DlnaDevice) -> Unit = { device ->
        castScope.launch {
            castError = null
            dlnaCaster.play(device, episode.url, "${movie.name} · ${episode.title}")
                .onSuccess {
                    castingDevice = device
                    session.pause(player)
                    castDialogVisible = false
                    seekGesturePrompt = "已投屏到 ${device.name}"
                    seekGesturePromptNonce++
                }
                .onFailure { error ->
                    castError = error.message ?: "投屏失败"
                }
        }
    }
    LaunchedEffect(castDialogVisible, castSearchNonce) {
        if (!canCast || !castDialogVisible) return@LaunchedEffect
        castDiscovering = true
        castError = null
        runCatching { dlnaCaster.discover() }
            .onSuccess { devices -> castDevices = devices }
            .onFailure { error -> castError = error.message ?: "搜索投屏设备失败" }
        castDiscovering = false
    }
    DisposableEffect(Unit) {
        onDispose {
            val device = castingDevice
            if (device != null) {
                // 先发出停止投屏请求，请求完成后再释放页面持有的作用域，避免泄漏 Job。
                castStopScope.launch { dlnaCaster.stop(device) }
                    .invokeOnCompletion { castStopScope.cancel() }
            } else {
                castStopScope.cancel()
            }
        }
    }

    val handlePlaybackIssue = issue@ { issueType: PlaybackIssueType, switchPrefix: String, finalPrefix: String, message: String ->
        val currentState = actions.state.value
        if (!session.canPlay || !isCurrentPlayback()) return@issue
        val resumePosition = resumeAnchor.positionForFailure(player.currentPosition)
        saveCurrentProgress(resumePosition, player.duration.takeIf { it > 0 } ?: 0)
        val failedSources = failedSourceIndexes + currentState.playerSourceIndex
        failedSourceIndexes = failedSources
        val issueToRecord = if (attemptTracker.shouldRecordIssue(currentState.currentPlaybackKey(), issueType)) {
            issueType
        } else {
            null
        }
        val decision = actions.switchToNextPlayableSource(
            blockedSourceIndexes = failedSources,
            positionMs = resumePosition,
            playWhenReady = player.playWhenReady,
            issueType = issueToRecord,
            autoTriggered = true,
        )
        if (decision.switched) {
            bufferingPlaybackKey = null
            playbackIssue = null
            playbackNotice = decision.toPlaybackNotice(switchPrefix)
            playbackNoticeNonce++
        } else {
            playbackNotice = null
            val errorMessage = if (!currentState.appSettings.playbackAgentAutoSwitchEnabled) {
                "$message（播放管家自动换线已关闭）"
            } else if (currentState.detailMovie?.playSources.orEmpty().size > 1) {
                "$finalPrefix：$message"
            } else {
                message
            }
            playbackIssue = PlaybackIssueNotice(issueType, errorMessage)
        }
    }

    val handleBufferDecision = { decision: PlaybackBufferDecision? ->
        if (decision != null) {
            val messages = decision.toPlaybackIssueMessages()
            handlePlaybackIssue(
                PlaybackIssueType.SlowBuffer,
                messages.switchPrefix,
                messages.finalPrefix,
                messages.message,
            )
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.playerError != error) return
                // 不展示 ExoPlayer 原始异常文本，按错误码映射为可读提示；
                // 能分级时追加可操作的自愈建议（重试 / 换线路 / 换来源）。
                val message = appendPlaybackErrorHint(
                    playbackErrorMessage(error.errorCode),
                    error.message,
                )
                handlePlaybackIssue(
                    PlaybackIssueType.Error,
                    "播放管家：当前线路播放失败",
                    "播放失败，播放管家已尝试所有可用线路",
                    message,
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!isCurrentPlayback() || player.playWhenReady != playWhenReady) return
                actions.updatePlayerPlayIntent(playWhenReady)
                if (!playWhenReady) {
                    bufferMonitor.onPaused()
                    playbackWatchdog.onPaused()
                    bufferingPlaybackKey = null
                }
            }

            override fun onRenderedFirstFrame() {
                playbackWatchdog.onFirstFrameRendered(System.currentTimeMillis())
                firstFrameRendered = true
                firstFrameFailure = null
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    if (loadedState.playerSourceIndex != actions.state.value.playerSourceIndex ||
                        loadedState.copy(playerEpisodeIndex = player.currentMediaItemIndex).currentPlaybackKey() !=
                        player.currentMediaItem?.mediaId) return
                    resumeAnchor.update(newPosition.positionMs)
                    bufferMonitor.onSeekStarted(System.currentTimeMillis())
                    bufferingPlaybackKey = null
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentState = actions.state.value
                if (loadedState.playerSourceIndex != currentState.playerSourceIndex ||
                    loadedState.detailMovie?.apiLineId != currentState.detailMovie?.apiLineId ||
                    loadedState.detailMovie?.id != currentState.detailMovie?.id) return
                val episodeIndex = player.currentMediaItemIndex
                if (loadedState.copy(playerEpisodeIndex = episodeIndex).currentPlaybackKey() != mediaItem?.mediaId) return
                val episodes = loadedState.detailMovie
                    ?.playSources
                    ?.getOrNull(loadedState.playerSourceIndex)
                    ?.episodes
                    .orEmpty()
                if (episodeIndex in episodes.indices && episodeIndex != currentState.playerEpisodeIndex) {
                    resumeAnchor.update(player.currentPosition)
                    failedSourceIndexes = emptySet()
                    outroSkipHandledKey = null
                    playbackIssue = null
                    playbackNotice = null
                    playbackNoticeNonce = 0
                    bufferingPlaybackKey = null
                    bufferMonitor.onMediaChanged()
                    actions.syncPlayerEpisode(episodeIndex)
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                // 长按 2x 是瞬时倍速，不能写进用户偏好；松手恢复时也不该重复落盘。
                if (longPressSpeedActive) return
                actions.updatePlaybackSpeed(playbackParameters.speed)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!isCurrentPlayback()) return
                val displayMode = videoSize.toVideoDisplayMode()
                if (displayMode == VideoDisplayMode.Unknown || displayMode == videoDisplayMode) return

                videoDisplayMode = displayMode
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrentPlayback() || player.playbackState != playbackState) return
                val currentState = actions.state.value
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        val decision = bufferMonitor.onBuffering(
                            playWhenReady = player.playWhenReady,
                            nowMs = System.currentTimeMillis(),
                        )
                        val playbackKey = currentState.currentPlaybackKey()
                        if (player.playWhenReady && playbackKey != null && bufferingPlaybackKey != playbackKey) {
                            bufferingPlaybackKey = playbackKey
                            bufferingCheckNonce++
                        }
                        handleBufferDecision(decision)
                    }
                    Player.STATE_READY -> {
                        bufferingPlaybackKey = null
                        if (introSkipPending) {
                            introSkipPending = false
                            val skipMs = actions.state.value.appSettings.introSkipSeconds.toLong() * 1_000L
                            val duration = player.duration
                            if (skipMs > 0 && duration > 0 && player.currentPosition < skipMs) {
                                player.seekTo(skipMs.coerceAtMost((duration - 1_000L).coerceAtLeast(0L)))
                            }
                        }
                        resumeAnchor.update(player.currentPosition)
                        playbackIssue = playbackIssue?.afterReady()
                        playbackNotice = null
                        playbackNoticeNonce = 0
                        failedSourceIndexes = emptySet()
                        val result = bufferMonitor.onReady(
                            playWhenReady = player.playWhenReady,
                            nowMs = System.currentTimeMillis(),
                        )
                        if (
                            result.shouldRecordPlaybackSuccess &&
                            attemptTracker.shouldRecordSuccess(currentState.currentPlaybackKey())
                        ) {
                            actions.recordPlaybackSuccess()
                        }
                    }
                    Player.STATE_ENDED -> {
                        bufferingPlaybackKey = null
                        saveCurrentProgress(
                            positionMs = player.currentPosition,
                            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                        )
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            saveCurrentProgress(
                positionMs = player.currentPosition,
                durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            )
            player.removeListener(listener)
        }
    }

    LaunchedEffect(activity, isTelevision, videoDisplayMode) {
        val requested = if (isTelevision) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else videoDisplayMode.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (activity != null && activity.requestedOrientation != requested) {
            if (isCurrentPlayback()) {
                saveCurrentProgress(player.currentPosition, player.duration.takeIf { it > 0 } ?: 0)
            }
            activity.requestedOrientation = requested
        }
    }
    DisposableEffect(activity, initialRequestedOrientation, isTelevision) {
        onDispose {
            // 与直播/平台直播播放页一致：退出后恢复进入前的方向设置，
            // 而不是把原本 UNSPECIFIED 或用户锁定的方向强制改成 PORTRAIT/LANDSCAPE。
            activity?.requestedOrientation = initialRequestedOrientation
        }
    }

    LaunchedEffect(player, state.playerSourceIndex, source.episodes) {
        if (castingDevice != null) stopCasting()
        loadedState = state
        // 重建播放器（切换解码策略）后按本地种子续播；首次进入仍沿用页面传入的起点。
        val rebuilt = rebuildSeed.consume()
        val startIndex = rebuilt?.first ?: state.playerEpisodeIndex
        val startPositionMs = rebuilt?.second ?: state.playerStartPositionMs
        resumeAnchor.update(startPositionMs)
        playbackIssue = null
        bufferingPlaybackKey = null
        bufferMonitor.onMediaChanged()
        // 建源即开始首帧计时；READY 只代表解码器就绪，真正出画面由 onRenderedFirstFrame 收尾。
        playbackWatchdog.onSourceRequested(System.currentTimeMillis())
        firstFrameRendered = false
        firstFrameFailure = null
        attemptTracker.onPlaybackChanged(state.currentPlaybackKey())
        // 从头播放时应用“开始跳过”；恢复进度时保持原位置。
        introSkipPending = startPositionMs <= 0L
        outroSkipHandledKey = null
        player.setMediaItems(
            source.episodes.mapIndexed { index, item ->
                MediaItem.Builder().setUri(item.url)
                    .setMediaId(state.copy(playerEpisodeIndex = index).currentPlaybackKey().orEmpty()).build()
            },
            startIndex,
            startPositionMs,
        )
        player.prepare()
        player.setPlaybackSpeed(state.playerSpeed)
        if (!state.playerPlayWhenReady) session.pause(player) else session.preparePlayback(player)
        saveCurrentProgress(
            positionMs = startPositionMs,
            durationMs = 0L,
        )
    }

    // 首帧看门狗 + 出帧后的卡死采样：可见时才计时，暂停/退后台不计入；超时后进入明确的"重试/下一线路"出口。
    LaunchedEffect(player, lifecycleOwner, movie.apiLineId, movie.id) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1_000L)
                if (!isCurrentPlayback() || firstFrameFailure != null) continue
                val now = System.currentTimeMillis()
                val playIntent = player.playWhenReady && !session.interrupted
                val failure = playbackWatchdog.checkFirstFrame(nowMs = now, playWhenReady = playIntent)
                    // READY 但进度长时间不推进才是真正卡死；缓冲态交给 PlaybackBufferMonitor 处理，
                    // 避免拖动进度条后的短暂缓冲被误判成卡死。
                    ?: playbackWatchdog.onProgressSample(
                        isPlaying = playIntent && player.playbackState == Player.STATE_READY,
                        positionMs = player.currentPosition,
                        nowMs = now,
                    )
                if (failure != null) firstFrameFailure = failure.kind
            }
        }
    }
    LaunchedEffect(firstFrameFailure) {
        if (isTelevision && firstFrameFailure != null) {
            runCatching { overlayRetryFocus.requestFocus() }
        }
    }

    PromptTimer(nonce = playbackNoticeNonce, hideDelayMs = 2_000L) { playbackNotice = null; playbackNoticeNonce = 0 }
    LaunchedEffect(bufferingCheckNonce, bufferingPlaybackKey) {
        val watchedPlaybackKey = bufferingPlaybackKey ?: return@LaunchedEffect
        delay(PlaybackBufferMonitor.DEFAULT_CONTINUOUS_BUFFER_THRESHOLD_MS)
        if (
            bufferingPlaybackKey == watchedPlaybackKey &&
            actions.state.value.currentPlaybackKey() == watchedPlaybackKey && isCurrentPlayback() &&
            player.playbackState == Player.STATE_BUFFERING
        ) {
            val decision = bufferMonitor.onBuffering(
                playWhenReady = player.playWhenReady,
                nowMs = System.currentTimeMillis(),
            )
            handleBufferDecision(decision)
        }
    }

    LaunchedEffect(player, lifecycleOwner) {
        // 仅在可见且确实在推进播放时周期保存：暂停/后台时位置不变，反复写历史
        // 会每 5 秒触发一次全量状态更新并产生无意义的磁盘写入。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(5_000L)
                if (!isCurrentPlayback() || !player.playWhenReady) continue
                saveCurrentProgress(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                )
            }
        }
    }

    LaunchedEffect(state.playerSpeed) {
        player.setPlaybackSpeed(state.playerSpeed)
    }

    LaunchedEffect(Unit) {
        runCatching { playerFocusRequester.requestFocus() }
    }

    PromptTimer(nonce = seekGesturePromptNonce, hideDelayMs = 1_000L) { seekGesturePrompt = null }

    val showLongPressSpeedPrompt = {
        seekGesturePrompt = "2x 倍速播放"
        seekGesturePromptNonce++
    }
    val togglePlaybackByGesture = {
        if (chrome.locked) {
            chrome.bump()
            seekGesturePrompt = "已锁定，先解锁"
        } else if (player.playWhenReady) {
            bufferMonitor.onPaused()
            session.pause(player)
            seekGesturePrompt = "暂停"
        } else {
            session.play(player)
            seekGesturePrompt = "播放"
        }
        seekGesturePromptNonce++
    }
    val seekByGesture = { deltaMs: Long, label: String ->
        if (chrome.locked) {
            chrome.bump()
            seekGesturePrompt = "已锁定，先解锁"
            seekGesturePromptNonce++
        } else {
            bufferMonitor.onSeekStarted(System.currentTimeMillis())
            val targetPosition = player.seekByOffset(deltaMs)
            seekGesturePrompt = "$label  ${formatPlayerTime(targetPosition)}"
            seekGesturePromptNonce++
            saveCurrentProgress(
                positionMs = targetPosition,
                durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            )
        }
    }
    val seekByRemote = { deltaMs: Long ->
        bufferMonitor.onSeekStarted(System.currentTimeMillis())
        val targetPosition = player.seekByOffset(deltaMs)
        val action = if (deltaMs < 0L) "快退 10 秒" else "快进 10 秒"
        val durationMs = player.duration.takeIf { it > 0L }
        seekGesturePrompt = if (durationMs == null) {
            "$action  ${formatPlayerTime(targetPosition)}"
        } else {
            "$action  ${formatPlayerTime(targetPosition)} / ${formatPlayerTime(durationMs)}"
        }
        seekGesturePromptNonce++
    }
    val finishRemoteSeek = {
        saveCurrentProgress(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
        )
    }
    val changeEpisodeByRemote = { offset: Int ->
        val currentIndex = player.currentMediaItemIndex
        val targetIndex = currentIndex + offset
        if (targetIndex in source.episodes.indices) {
            player.seekTo(targetIndex, 0L)
            session.play(player)
            val action = if (offset < 0) "上一集" else "下一集"
            seekGesturePrompt = "$action  ${source.episodes[targetIndex].title}"
        } else {
            seekGesturePrompt = if (offset < 0) "已经是第一集" else "已经是最后一集"
        }
        seekGesturePromptNonce++
    }
    val cyclePlaybackSpeedByRemote = {
        val nextSpeed = nextPlaybackSpeed(latestState.playerSpeed)
        player.setPlaybackSpeed(nextSpeed)
        actions.updatePlaybackSpeed(nextSpeed)
        seekGesturePrompt = "当前倍速 ${formatPlaybackSpeed(nextSpeed)}"
        seekGesturePromptNonce++
    }

    // 遥控键位收敛到共享 PlayerSeekKeyMap：页面只提供语义回调，不含键码判断。
    val seekKeyMap = remember {
        PlayerSeekKeyMap(
            PlayerSeekKeyMap.Callbacks(
                onSeekBy = {},
                onFinishSeek = {},
                onToggle = {},
                onPlay = {},
                onPause = {},
                onChangeEpisode = {},
                onCycleSpeed = {},
            ),
        )
    }
    seekKeyMap.callbacks = PlayerSeekKeyMap.Callbacks(
        onSeekBy = { seekByRemote(it) },
        onFinishSeek = { finishRemoteSeek() },
        onToggle = { togglePlaybackByGesture() },
        onPlay = {
            session.play(player)
            seekGesturePrompt = "播放"
            seekGesturePromptNonce++
        },
        onPause = {
            bufferMonitor.onPaused()
            session.pause(player)
            seekGesturePrompt = "暂停"
            seekGesturePromptNonce++
        },
        onChangeEpisode = { changeEpisodeByRemote(it) },
        onCycleSpeed = { cyclePlaybackSpeedByRemote() },
    )

    val touchController = remember {
        PlayerTouchGestureHandler(
            activity = activity,
            audioManager = audioManager,
            capabilities = PlayerTouchCapabilities(),
            callbacks = PlayerTouchCallbacks(
                isLocked = { false },
                currentPositionMs = { 0L },
                durationMs = { null },
                onToggleControls = {},
                onLockedTap = {},
                onPlaybackToggle = {},
                onSeekBy = { _, _ -> },
                onSeekDragPreview = { _, _ -> },
                onSeekDragCommit = {},
                onBrightnessChanged = { _, _ -> },
                onVolumeChanged = { _, _ -> },
                onLongPressStart = {},
                onLongPressEnd = {},
            ),
        )
    }
    touchController.callbacks = PlayerTouchCallbacks(
        isLocked = { chrome.locked },
        currentPositionMs = { player.currentPosition.coerceAtLeast(0L) },
        durationMs = { player.duration.takeIf { it > 0L } },
        onToggleControls = { chrome.toggle() },
        onLockedTap = {
            chrome.bump()
            seekGesturePrompt = "已锁定，点“解锁”恢复"
            seekGesturePromptNonce++
        },
        onPlaybackToggle = { togglePlaybackByGesture() },
        onSeekBy = { deltaMs, label -> seekByGesture(deltaMs, label) },
        onSeekDragPreview = { positionMs, durationMs ->
            seekGesturePrompt = "进度 ${formatPlayerTime(positionMs)} / ${formatPlayerTime(durationMs)}"
            seekGesturePromptNonce++
        },
        onSeekDragCommit = { positionMs ->
            bufferMonitor.onSeekStarted(System.currentTimeMillis())
            player.seekTo(positionMs)
            seekGesturePromptNonce++
            saveCurrentProgress(
                positionMs = positionMs,
                durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            )
        },
        onBrightnessChanged = { brightness, percent ->
            seekGesturePrompt = "亮度 $percent%"
            seekGesturePromptNonce++
        },
        onVolumeChanged = { volume, maxVolume ->
            seekGesturePrompt = "音量 $volume/$maxVolume"
            seekGesturePromptNonce++
        },
        onLongPressStart = {
            if (!longPressSpeedActive) longPressOriginalSpeed = player.playbackParameters.speed
            longPressSpeedActive = true
            player.setPlaybackSpeed(LONG_PRESS_PLAYBACK_SPEED)
            showLongPressSpeedPrompt()
        },
        onLongPressEnd = {
            longPressSpeedActive = false
            player.setPlaybackSpeed(longPressOriginalSpeed)
            seekGesturePrompt = null
        },
    )
    DisposableEffect(player, activity, initialScreenBrightness) {
        onDispose {
            touchController.release()
            activity?.window?.restoreScreenBrightness(initialScreenBrightness)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .playerKeyInput(seekKeyMap, chrome)
            .focusable(),
    ) {
        PlayerSurface(
            player = player,
            resizeMode = videoScaleMode,
            showBuffering = false,
            configure = { view ->
                // 电视模式不挂触摸手势：电视上没有触摸源，误触/触摸板会意外改亮度、音量或进度。
                if (!isTelevision) {
                    view.setOnTouchListener(touchController)
                }
            },
        )
        val failureKind = firstFrameFailure
        val overlayStatus = when {
            failureKind != null -> PlaybackStatus.Failed(
                kind = failureKind,
                title = playbackFailureTitle(failureKind),
                message = playbackFailureMessage(failureKind),
                nextLabel = if (movie.playSources.size > 1) "下一线路" else null,
            )
            player.playWhenReady && !firstFrameRendered && !session.interrupted &&
                chrome.panel == PlayerPanel.None -> PlaybackStatus.Loading("正在加载 ${movie.name}…")
            else -> PlaybackStatus.Hidden
        }
        PlaybackStatusOverlay(
            status = overlayStatus,
            onRetry = {
                firstFrameFailure = null
                firstFrameRendered = false
                playbackWatchdog.onSourceRequested(System.currentTimeMillis())
                player.prepare()
                session.play(player)
            },
            onNext = if (movie.playSources.size > 1) {
                {
                    val nextIndex = (state.playerSourceIndex + 1) % movie.playSources.size
                    firstFrameFailure = null
                    saveCurrentProgress(player.currentPosition, uiDuration)
                    actions.switchPlayerSource(
                        nextIndex,
                        resumeAnchor.positionForFailure(player.currentPosition),
                        playWhenReady = true,
                    )
                }
            } else {
                null
            },
            modifier = Modifier.align(Alignment.Center),
            retryFocusRequester = if (isTelevision) overlayRetryFocus else null,
        )
        val currentEpisodeIndex = player.currentMediaItemIndex.coerceIn(0, source.episodes.lastIndex)
        val currentEpisodeTitle = source.episodes[currentEpisodeIndex].title
        val changeEpisode = { index: Int ->
            if (index in source.episodes.indices) {
                saveCurrentProgress(player.currentPosition, uiDuration)
                val skipMs = state.appSettings.introSkipSeconds.toLong() * 1_000L
                player.seekTo(index, skipMs)
                introSkipPending = skipMs > 0L
                session.play(player)
            }
        }
        PlayerChrome(
            state = chrome,
            data = PlayerChromeData(
                title = movie.name,
                episodes = source.episodes.map { it.title },
                sources = movie.playSources.map { PlayerChromeSource(name = it.name, lineId = it.lineId) },
                episodeIndex = currentEpisodeIndex,
                sourceIndex = state.playerSourceIndex,
                sourceEnabled = movie.playSources.map { candidate ->
                    correspondingEpisodeIndex(candidate, currentEpisodeTitle, currentEpisodeIndex) != null
                },
                positionMs = uiPosition,
                durationMs = uiDuration,
                playing = uiPlaying,
                speed = state.playerSpeed,
                resizeMode = videoScaleMode,
                sourceLatencies = state.sourceLatencies,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                audioTracks = playerTracks.audioTracks,
                subtitleTracks = playerTracks.subtitleTracks,
                playbackInfo = diagnostics?.info,
                decoderStrategy = state.appSettings.decoderStrategy,
                decoderFallbackKinds = HardwareDecoderFallbackNotice.kinds,
                playbackUrls = listOf(
                    PlayerChromeUrl(
                        label = "实际播放地址（点播线路）",
                        url = source.episodes[currentEpisodeIndex].url,
                    ),
                ),
            ),
            capabilities = PlayerChromeCapabilities(
                seek = true,
                episodes = true,
                sources = true,
                speed = true,
                resize = true,
                skip = true,
                audioTracks = true,
                subtitleTracks = true,
                info = true,
                url = true,
                cast = canCast,
                castLabel = if (canCast) {
                    if (castingDevice != null) "停止投屏" else "投屏"
                } else {
                    null
                },
                lock = true,
            ),
            actions = PlayerChromeActions(
                onToggle = { togglePlaybackByGesture() },
                onSeek = { position ->
                    bufferMonitor.onSeekStarted(System.currentTimeMillis())
                    player.seekTo(position)
                    saveCurrentProgress(position, uiDuration)
                },
                onSeekBy = { delta ->
                    seekByGesture(delta, if (delta < 0) "快退 ${-delta / 1000} 秒" else "快进 ${delta / 1000} 秒")
                },
                onPrevious = { changeEpisode(currentEpisodeIndex - 1) },
                onNext = { changeEpisode(currentEpisodeIndex + 1) },
                onSelectEpisode = changeEpisode,
                onSelectSource = { index ->
                    saveCurrentProgress(player.currentPosition, uiDuration)
                    actions.switchPlayerSource(index, resumeAnchor.positionForFailure(player.currentPosition), player.playWhenReady)
                },
                onSelectSpeed = { speed ->
                    player.setPlaybackSpeed(speed)
                    actions.updatePlaybackSpeed(speed)
                },
                onSelectResize = { mode -> actions.updateContentVideoScaleMode(videoScaleContentKey, mode) },
                onSelectAudioTrack = { player.selectAudioTrack(it) },
                onSelectSubtitleTrack = { player.selectSubtitleTrack(it) },
                onBack = {
                    saveCurrentProgress(player.currentPosition, uiDuration)
                    actions.goBack()
                },
                onCast = {
                    if (castingDevice != null) stopCasting() else castDialogVisible = true
                },
                onLockChange = { locked ->
                    chrome.interaction++
                    if (locked) {
                        touchController.cancelPendingGestures()
                    }
                    seekGesturePrompt = if (locked) "播放已锁定" else "已解锁"
                    seekGesturePromptNonce++
                },
                onInteraction = { chrome.interaction++ },
            ),
            touch = isMobile,
            episodeSortReverse = state.appSettings.episodeSortReverse,
            onSortReverse = { actions.toggleEpisodeSortReverse() },
            introSkipSeconds = state.appSettings.introSkipSeconds,
            outroSkipSeconds = state.appSettings.outroSkipSeconds,
            onIntroSkip = { actions.updateIntroSkipSeconds(it) },
            onOutroSkip = { actions.updateOutroSkipSeconds(it) },
        )
        val playbackStatus = playbackIssue?.message ?: playbackNotice
        if (playbackStatus != null) {
            GesturePrompt(
                text = playbackStatus,
                textColor = if (playbackIssue != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 112.dp),
            )
        }
        if (seekGesturePrompt != null) {
            GesturePrompt(
                text = seekGesturePrompt.orEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 164.dp),
            )
        }
        if (canCast && castDialogVisible) {
            AlertDialog(
                onDismissRequest = { castDialogVisible = false },
                title = { Text("投屏到电视") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("仅手机端可用；请确保手机与电视连接同一 Wi-Fi，且电视已开启 DLNA/UPnP 投屏。")
                        if (castDiscovering) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("正在搜索 DLNA/UPnP 设备…")
                        }
                        castError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                        if (castDevices.isNotEmpty()) {
                            castDevices.forEach { device ->
                                ControlButton(
                                    text = device.name,
                                    onClick = { startCasting(device) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else if (!castDiscovering) {
                            Text("未发现设备，请确认电视已开启 DLNA/UPnP 投屏后再重新搜索。")
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { castSearchNonce++ }) { Text("重新搜索") }
                },
                dismissButton = {
                    Button(onClick = { castDialogVisible = false }) { Text("关闭") }
                },
            )
        }
    }
}

@Composable
private fun GesturePrompt(
    text: String,
    textColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xB8000000))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
    }
}

/**
 * 重建播放器（切换解码策略）时的续播种子。
 * 旧播放器离开组合时记录一次，新播放器建源后立即消费，避免影响后续换源/换集。
 */
private class PlaybackRebuildSeed {
    private var episodeIndex: Int = -1
    private var positionMs: Long = 0L

    fun remember(player: ExoPlayer) {
        if (player.currentPosition > 0L && player.currentMediaItemIndex >= 0) {
            episodeIndex = player.currentMediaItemIndex
            positionMs = player.currentPosition
        }
    }

    fun consume(): Pair<Int, Long>? {
        if (episodeIndex < 0) return null
        val seed = episodeIndex to positionMs
        episodeIndex = -1
        positionMs = 0L
        return seed
    }
}

private data class PlaybackIssueMessages(
    val switchPrefix: String,
    val finalPrefix: String,
    val message: String,
)

private fun TvBoxUiState.currentPlaybackKey(): String? {
    val movie = detailMovie ?: return null
    val source = movie.playSources.getOrNull(playerSourceIndex) ?: return null
    val episode = source.episodes.getOrNull(playerEpisodeIndex) ?: return null
    return listOf(movie.apiLineId, movie.id, playerSourceIndex, playerEpisodeIndex, episode.url).joinToString("|")
}

private fun PlaybackBufferDecision.toPlaybackIssueMessages(): PlaybackIssueMessages {
    return when (reason) {
        SlowBufferReason.StartupTooLong,
        SlowBufferReason.ContinuousBufferTooLong,
        -> PlaybackIssueMessages(
            switchPrefix = "播放管家：当前线路缓冲超过 5 秒",
            finalPrefix = "当前线路缓冲超过 5 秒，播放管家已尝试所有可用线路",
            message = "当前线路缓冲超过 5 秒",
        )
        SlowBufferReason.FrequentBuffering -> PlaybackIssueMessages(
            switchPrefix = "播放管家：当前线路频繁卡顿",
            finalPrefix = "当前线路频繁卡顿，播放管家已尝试所有可用线路",
            message = "当前线路频繁卡顿",
        )
        SlowBufferReason.CumulativeBufferTooLong -> PlaybackIssueMessages(
            switchPrefix = "播放管家：当前线路累计缓冲过久",
            finalPrefix = "当前线路累计缓冲过久，播放管家已尝试所有可用线路",
            message = "当前线路累计缓冲过久",
        )
    }
}

private fun ExoPlayer.seekByOffset(deltaMs: Long): Long {
    val durationMs = duration.takeIf { it > 0L }
    val targetPosition = (currentPosition + deltaMs)
        .coerceAtLeast(0L)
        .let { position -> durationMs?.let { position.coerceAtMost(it) } ?: position }
    seekTo(targetPosition)
    return targetPosition
}

private fun VideoSize.toVideoDisplayMode(): VideoDisplayMode {
    if (width <= 0 || height <= 0) return VideoDisplayMode.Unknown
    val aspectRatio = width.toFloat() * pixelWidthHeightRatio / height
    return when {
        aspectRatio <= PORTRAIT_VIDEO_ASPECT_RATIO_MAX -> VideoDisplayMode.Portrait
        aspectRatio >= LANDSCAPE_VIDEO_ASPECT_RATIO_MIN -> VideoDisplayMode.Landscape
        else -> VideoDisplayMode.Unknown
    }
}

private enum class VideoDisplayMode(
    val requestedOrientation: Int? = null,
) {
    Unknown,
    Portrait(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    Landscape(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
}

private const val LONG_PRESS_PLAYBACK_SPEED = 2f
private const val PORTRAIT_VIDEO_ASPECT_RATIO_MAX = 0.8f
private const val LANDSCAPE_VIDEO_ASPECT_RATIO_MIN = 1.1f
private val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

private fun nextPlaybackSpeed(currentSpeed: Float): Float {
    val currentIndex = PLAYBACK_SPEEDS.indexOfFirst { abs(it - currentSpeed) < 0.01f }
    return PLAYBACK_SPEEDS[(currentIndex + 1).coerceAtLeast(0) % PLAYBACK_SPEEDS.size]
}

private fun formatPlaybackSpeed(speed: Float): String {
    return "${speed.toString().trimEnd('0').trimEnd('.')}x"
}

private fun PlaybackAgentDecision.toPlaybackNotice(prefix: String): String {
    val sourceName = nextSourceName
    return if (sourceName.isNullOrBlank()) {
        "$prefix，正在自动切换下一条线路"
    } else {
        "$prefix，已切换到 $sourceName"
    }
}
