package com.tvbox.app.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.annotation.OptIn
// #262/#50：切集淡入淡出用 alpha 动画驱动黑色遮罩，不重建 ExoPlayer。
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.tvbox.app.crash.CrashHandler
import com.tvbox.app.util.TLog
import com.tvbox.app.data.DlnaCaster
import com.tvbox.app.data.DlnaDevice
import com.tvbox.app.domain.PlaybackAgentDecision
import com.tvbox.app.domain.GlobalPlayState
import com.tvbox.app.domain.PlaybackFailureCode
import com.tvbox.app.domain.appendPlaybackErrorHint
import com.tvbox.app.domain.effectiveDecoderStrategy
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
import com.tvbox.app.domain.PlayerBrightnessSetting
import com.tvbox.app.domain.PlayerSpeedSetting
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.domain.resolveVideoScaleMode
import com.tvbox.app.domain.vodVideoScaleKey
import com.tvbox.app.domain.SlowBufferReason
import com.tvbox.app.domain.SkipCreditsAction
import com.tvbox.app.domain.SkipCreditsConfig
import com.tvbox.app.domain.advanceSkipCredits
import com.tvbox.app.domain.resetSkipCreditsRuntime
import com.tvbox.app.domain.resolveSkipCredits
import com.tvbox.app.domain.nextPlaybackSpeed
import com.tvbox.app.domain.PLAYER_SPEED_STEPS
import com.tvbox.app.util.logSafeText
import com.tvbox.app.ui.components.EmptyState
import com.tvbox.app.ui.theme.TvColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // 播放器相关偏好：统一在本页取值，避免每个回调各读一份 state 造成口径漂移。
    val playbackSettings = state.appSettings
    val gesturesEnabled = playbackSettings.playerGesturesEnabled
    val autoNextEnabled = playbackSettings.autoNextEpisodeEnabled
    val reconnectAttempts = playbackSettings.reconnectAttempts
    val firstPacketTimeoutMs = playbackSettings.firstPacketTimeoutMs.coerceAtLeast(1_000L)
    val seekCoolingMs = playbackSettings.seekCoolingMs.coerceAtLeast(0L)
    val backgroundPlaybackMode = playbackSettings.backgroundPlaybackMode
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
    // playerBrightness：0=跟随系统，1..100=按百分比设置窗口亮度；手势改过的值在下次进入时由初值快照恢复。
    val configuredBrightness = playbackSettings.playerBrightness
    LaunchedEffect(activity, configuredBrightness, initialScreenBrightness) {
        if (activity == null) return@LaunchedEffect
        val value = PlayerBrightnessSetting.clamp(configuredBrightness)
        if (value > 0) {
            activity.window?.setScreenBrightness(value / 100f)
        } else {
            activity.window?.restoreScreenBrightness(initialScreenBrightness)
        }
    }
    // #54/#266 设备型号命中硬解白名单时强制硬解，否则沿用用户选择的解码策略。
    // 缓冲目标与音频输出在构建期生效，设置变化时重建播放器（页面会按重建种子续播）。
    val player = rememberManagedExoPlayer(
        decoderStrategy = playbackSettings.effectiveDecoderStrategy(),
        bufferTargetMs = playbackSettings.bufferTargetMs,
        audioOutputMode = playbackSettings.audioOutputMode,
    )
    val playbackHost = rememberPlaybackHost(player)
    // 系统媒体键（含电视遥控器多媒体键/耳机线控）在应用未获焦时也能控制播放/暂停与快进快退。
    rememberPlayerMediaSession(player = player)
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
    // 字幕轨道按"影片 + 集"记忆：首次轨道就绪时应用一次，用户手动切换后不再覆盖。
    var subtitleMemoryApplied by remember(movie.apiLineId, movie.id) { mutableStateOf(false) }
    LaunchedEffect(player, videoScaleContentKey, playerTracks.subtitleTracks) {
        if (!subtitleMemoryApplied && playerTracks.subtitleTracks.isNotEmpty()) {
            val remembered = state.appSettings.contentSubtitleTracks[videoScaleContentKey]
            if (remembered != null) player.selectSubtitleTrack(remembered)
            subtitleMemoryApplied = true
        }
    }
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
    // 退出播放页的统一出口：先保存当前进度，再返回详情页。
    // 屏幕上的「返回」按钮与硬件返回键（控件已隐藏时）共用同一逻辑，避免分散保存/返回路径。
    fun exitPlayer() {
        saveCurrentProgress(player.currentPosition, player.duration.takeIf { it > 0 } ?: 0)
        actions.goBack()
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
            // 控件已隐藏时单次返回直接退出；退出前统一保存进度，避免少一次确认后漏存。
            exitPlayer()
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
    val uiPosition = playbackHost.progress.positionMs
    val uiDuration = playbackHost.progress.durationMs
    val uiPlaying = playbackHost.wantsToPlay
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
    // 手机触摸模式下控件保留更久（4s vs 5s 电视模式），避免误触后立刻消失让用户来不及操作。
    // PLAYER_CHROME_TOUCH_AUTO_HIDE_MS 是专门为触摸设备预置的超时，与 TvBoxApp 中的直播页逻辑保持一致。
    val chromeAutoHideMs = if (isMobile) PLAYER_CHROME_TOUCH_AUTO_HIDE_MS else PLAYER_CHROME_AUTO_HIDE_MS
    PlayerChromeAutoHide(chrome, uiPlaying, autoHideMs = chromeAutoHideMs)
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
    // 共享看门狗：从建源起算首帧，出帧后才开始判卡死；首包超时取用户设置。
    val playbackWatchdog = remember(firstPacketTimeoutMs) {
        PlaybackWatchdog(firstFrameTimeoutMs = firstPacketTimeoutMs)
    }
    var firstFrameRendered by remember(movie.apiLineId, movie.id) { mutableStateOf(false) }
    // 自动重连已尝试次数：首帧成功即清零；reconnectAttempts 控制上限（-1 表示不设上限）。
    var autoReconnectTried by remember(movie.apiLineId, movie.id) { mutableIntStateOf(0) }
    // 最近一次 seek 时刻：冷却窗口内不判定卡死，避免拖动后的短暂缓冲被误报。
    var lastSeekAtMs by remember(movie.apiLineId, movie.id) { mutableLongStateOf(0L) }
    // 片尾自动切集倒计时：0 表示没有待执行的切集。
    var autoNextCountdownUntilMs by remember(movie.apiLineId, movie.id) { mutableLongStateOf(0L) }
    var autoNextCountdownRemaining by remember(movie.apiLineId, movie.id) { mutableIntStateOf(0) }
    var lastPinchSwitchAtMs by remember { mutableLongStateOf(0L) }
    // #262/#50：切集过渡遮罩。只叠一层短暂淡入淡出，不重建播放器、不打断续播队列。
    var episodeFadeActive by remember { mutableStateOf(false) }
    val episodeFadeAlpha by animateFloatAsState(
        targetValue = if (episodeFadeActive) 1f else 0f,
        animationSpec = tween(EPISODE_FADE_DURATION_MS),
        label = "episode-switch-fade",
    )
    LaunchedEffect(episodeFadeActive) {
        if (!episodeFadeActive) return@LaunchedEffect
        // 淡入结束后保持一小段时间再淡出，遮住旧画面最后一帧与新集首帧之间的空档。
        delay(EPISODE_FADE_HOLD_MS)
        episodeFadeActive = false
    }
    var firstFrameFailure by remember(movie.apiLineId, movie.id) {
        mutableStateOf<PlaybackFailureKind?>(null)
    }
    // 同类失败可能连续发生（换线后仍首帧超时），用序号让自动重连每次都能重新触发。
    var firstFrameFailureNonce by remember(movie.apiLineId, movie.id) { mutableIntStateOf(0) }
    // 点播无下一集且播放自然结束：显示"已播完"，可返回详情。
    var playbackFinished by remember(movie.apiLineId, movie.id) {
        mutableStateOf(false)
    }
    val overlayRetryFocus = remember { FocusRequester() }
    var bufferingPlaybackKey by remember { mutableStateOf<String?>(null) }
    var bufferingCheckNonce by remember { mutableIntStateOf(0) }
    var failedSourceIndexes by remember(movie.apiLineId, movie.id) {
        mutableStateOf(emptySet<Int>())
    }
    /** 新一集开始播放后等待 READY，再统一跳过片头。 */
    var introSkipPending by remember(movie.apiLineId, movie.id) { mutableStateOf(false) }
    /** SkipCredits 每集只触发一次的运行状态（outroTriggered 替代原 outroSkipHandledKey）。 */
    var skipCreditsState by remember(movie.apiLineId, movie.id) {
        mutableStateOf(resetSkipCreditsRuntime())
    }
    val latestState by rememberUpdatedState(state)

    // 片尾跳过：剩 N 秒时自动进入下一集（每集只触发一次）。
    // 判定统一走 domain 层 resolveSkipCredits + SkipCreditsRuntime（含时长边界守卫），
    // 替代原手工 remaining>outroSkipMs 判定，避免短时长+大 outro 配置在播放早期误触发下一集。
    LaunchedEffect(player, lifecycleOwner) {
        // 片尾检测同样只在可见时轮询：后台时播放已被会话暂停，
        // 继续 4 次/秒唤醒主线程既无意义又会干扰系统省电策略。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(250L)
                if (!isCurrentPlayback()) continue
                val settingsNow = actions.state.value.appSettings
                // 片头跳过与片尾切集共用配置，是否自动切集单独由 autoNextEpisodeEnabled 控制。
                val config = SkipCreditsConfig(
                    enabled = true,
                    introSeconds = settingsNow.introSkipSeconds,
                    outroSeconds = settingsNow.outroSkipSeconds,
                )
                if (!settingsNow.autoNextEpisodeEnabled && autoNextCountdownUntilMs != 0L) {
                    autoNextCountdownUntilMs = 0L
                    autoNextCountdownRemaining = 0
                }
                val duration = player.duration
                if (duration <= 0 || player.playbackState != Player.STATE_READY) continue
                // 不在片尾区间：若之前已触发过，重置状态以便下次进入片尾时可再次触发。
                val inOutro = resolveSkipCredits(player.currentPosition, duration, config)
                    ?.action == SkipCreditsAction.PlayNextEpisode
                if (!inOutro) {
                    if (skipCreditsState.outroTriggered) skipCreditsState = resetSkipCreditsRuntime()
                    if (autoNextCountdownUntilMs != 0L) {
                        autoNextCountdownUntilMs = 0L
                        autoNextCountdownRemaining = 0
                    }
                    continue
                }
                val playbackKey = actions.state.value
                    .copy(playerEpisodeIndex = player.currentMediaItemIndex)
                    .currentPlaybackKey()
                val (decision, newState) = advanceSkipCredits(
                    skipCreditsState, player.currentPosition, duration, config,
                )
                skipCreditsState = newState
                if (decision?.action != SkipCreditsAction.PlayNextEpisode) continue
                if (!settingsNow.autoNextEpisodeEnabled) continue
                val nextIndex = player.currentMediaItemIndex + 1
                if (player.isPlaying && playbackKey != null && nextIndex < player.mediaItemCount) {
                    val countdownSeconds = settingsNow.autoNextCountdownSeconds.coerceAtLeast(0)
                    if (countdownSeconds > 0 && autoNextCountdownUntilMs == 0L) {
                        // 数字倒计时：到点后由独立协程执行切集，期间仍在片尾区间内。
                        autoNextCountdownUntilMs = System.currentTimeMillis() + countdownSeconds * 1_000L
                        continue
                    }
                    if (autoNextCountdownUntilMs == 0L) {
                        saveCurrentProgress(player.currentPosition, duration)
                        player.seekTo(nextIndex, 0L)
                        session.play(player)
                    }
                }
            }
        }
    }
    // 片尾倒计时：可见时每秒刷新剩余秒数，到点后复用播放器队列切集，不重建实例。
    LaunchedEffect(autoNextCountdownUntilMs) {
        val deadline = autoNextCountdownUntilMs
        if (deadline <= 0L) return@LaunchedEffect
        while (autoNextCountdownUntilMs == deadline && System.currentTimeMillis() < deadline) {
            val remaining = ((deadline - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L).toInt()
            if (autoNextCountdownRemaining != remaining) autoNextCountdownRemaining = remaining
            delay(200L)
        }
        if (autoNextCountdownUntilMs != deadline) return@LaunchedEffect
        autoNextCountdownUntilMs = 0L
        autoNextCountdownRemaining = 0
        if (!autoNextEnabled || !isCurrentPlayback() || !player.playWhenReady) return@LaunchedEffect
        val nextIndex = player.currentMediaItemIndex + 1
        if (nextIndex >= player.mediaItemCount) return@LaunchedEffect
        saveCurrentProgress(player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L)
        episodeFadeActive = true
        player.seekTo(nextIndex, 0L)
        session.play(player)
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
                CrashHandler.recordOperation("player.onPlayerError", "code=${error.errorCode} msg=${error.message}")
                bufferMonitor.onPlaybackFailed()
                // 不展示 ExoPlayer 原始异常文本，按错误码映射为可读提示；
                // 能分级时追加可操作的自愈建议（重试 / 换线路 / 换来源）。
                // T3-22：结构化失败码进 OSD 文案与全局展示文本，用户可贴 E-xxx 定位。
                val failure = failureCodeFromExo(error.errorCode)
                val baseMessage = playbackErrorMessage(error.errorCode)
                val displayText = failure?.let {
                    "${it.code} · ${it.title}"
                } ?: baseMessage
                GlobalPlayState.lastFailureDisplayText = displayText
                // T3-23：失败出口取走 64 帧面包屑并只落本地诊断日志（不上传）。
                // drain 会清空缓冲，保证同一次故障不会重复落盘；下一次换源/失败重新累积。
                val frames = GlobalPlayState.breadcrumb.drainForFailure()
                if (frames.isNotEmpty()) {
                    val trail = frames.joinToString(" | ") { "${it.tag}:${it.detail}" }
                    CrashHandler.recordOperation(
                        "playback.failure",
                        "code=${failure?.code ?: "E-?"} breadcrumbs=$trail",
                    )
                    TLog.w("TVBoxBreadcrumb") { "失败面包屑 ${failure?.code ?: "E-?"} · $trail" }
                }
                val message = appendPlaybackErrorHint(
                    displayText,
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
                // 真正出过画面说明线路可用，自动重连次数重新计数。
                autoReconnectTried = 0
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
                    val nowMs = System.currentTimeMillis()
                    bufferMonitor.onSeekStarted(nowMs)
                    lastSeekAtMs = nowMs
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
                    playbackFinished = false
                    resumeAnchor.update(player.currentPosition)
                    failedSourceIndexes = emptySet()
                    skipCreditsState = resetSkipCreditsRuntime()
                    autoNextCountdownUntilMs = 0L
                    autoNextCountdownRemaining = 0
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
                        playbackFinished = false
                        if (introSkipPending) {
                            introSkipPending = false
                            val settings = actions.state.value.appSettings
                            val config = SkipCreditsConfig(
                                enabled = true,
                                introSeconds = settings.introSkipSeconds,
                                outroSeconds = settings.outroSkipSeconds,
                            )
                            val duration = player.duration
                            val decision = resolveSkipCredits(player.currentPosition, duration, config)
                            if (decision?.action == SkipCreditsAction.SeekToIntro && duration > 0) {
                                player.seekTo(
                                    decision.targetPositionMs.coerceAtMost((duration - 1_000L).coerceAtLeast(0L)),
                                )
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
                        // 连续播放：有下一集时自动续播；关闭片尾自动切集时停在片尾。
                        if (player.playWhenReady) {
                            if (autoNextEnabled && player.hasNextMediaItem()) {
                                playbackFinished = false
                                // #262/#50：切集时触发短暂黑场淡入淡出，复用播放器队列不重建实例。
                                episodeFadeActive = true
                                player.seekToNextMediaItem()
                            } else {
                                playbackFinished = true
                            }
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            // 防御：Compose 逆序 dispose 保证本 onDispose 先于 rememberManagedExoPlayer 的 release，
            // 但为避免未来注册顺序变化导致在已 release 的播放器上读取崩溃，读取包 try-catch。
            try {
                saveCurrentProgress(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                )
            } catch (e: Exception) {
                PlayerLog.w("TVBoxPlayer", "saveProgressOnDispose failed: ${logSafeText(e.message)}")
            }
            try {
                player.removeListener(listener)
            } catch (e: Exception) {
                PlayerLog.w("TVBoxPlayer", "removeListenerOnDispose failed: ${logSafeText(e.message)}")
            }
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
        CrashHandler.recordOperation(
            "player.startPlayback",
            "movie=${movie.id} line=${movie.apiLineId} ep=$startIndex pos=$startPositionMs",
        )
        playbackIssue = null
        bufferingPlaybackKey = null
        bufferMonitor.onMediaChanged()
        // 建源即开始首帧计时；READY 只代表解码器就绪，真正出画面由 onRenderedFirstFrame 收尾。
        playbackWatchdog.onSourceRequested(System.currentTimeMillis())
        firstFrameRendered = false
        firstFrameFailure = null
        autoReconnectTried = 0
        lastSeekAtMs = System.currentTimeMillis()
        autoNextCountdownUntilMs = 0L
        autoNextCountdownRemaining = 0
        attemptTracker.onPlaybackChanged(state.currentPlaybackKey())
        // 从头播放时应用“开始跳过”；恢复进度时保持原位置。
        introSkipPending = startPositionMs <= 0L
        skipCreditsState = resetSkipCreditsRuntime()
        player.setMediaItems(
            source.episodes.mapIndexed { index, item ->
                MediaItem.Builder().setUri(item.url)
                    .setMediaId(state.copy(playerEpisodeIndex = index).currentPlaybackKey().orEmpty()).build()
            },
            startIndex,
            startPositionMs,
        )
        player.prepare()
        player.setPlaybackSpeed(PlayerSpeedSetting.clamp(state.playerSpeed))
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
                    // READY 但进度长时间不推进才是真正卡死；缓冲态交给 PlaybackBufferMonitor 处理。
                    // seekCoolingMs 冷却窗口内不采样，避免手动拖动后的短暂缓冲被误判成卡死。
                    ?: if (now - lastSeekAtMs < seekCoolingMs) null
                    else playbackWatchdog.onProgressSample(
                        isPlaying = playIntent && player.playbackState == Player.STATE_READY,
                        positionMs = player.currentPosition,
                        nowMs = now,
                    )
                if (failure != null) {
                    firstFrameFailure = failure.kind
                    firstFrameFailureNonce++
                }
            }
        }
    }
    LaunchedEffect(firstFrameFailure) {
        if (isTelevision && firstFrameFailure != null) {
            runCatching { overlayRetryFocus.requestFocus() }
        }
    }
    // 首包超时自动换线：reconnectAttempts=0 关闭；正数按次数上限；-1 持续（受可用线路数约束）。
    LaunchedEffect(firstFrameFailure, firstFrameFailureNonce) {
        val failure = firstFrameFailure ?: return@LaunchedEffect
        if (failure != PlaybackFailureKind.FirstFrameTimeout) return@LaunchedEffect
        val attempts = actions.state.value.appSettings.reconnectAttempts
        if (attempts == 0) return@LaunchedEffect
        if (attempts > 0 && autoReconnectTried >= attempts) return@LaunchedEffect
        autoReconnectTried += 1
        handlePlaybackIssue(
            PlaybackIssueType.Error,
            "播放管家：首帧超时，正在自动换线",
            "首帧超时，播放管家已尝试所有可用线路",
            "首帧超时，正在自动换线",
        )
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
        // 设置值统一收敛到 0.5..3.0，避免异常持久化值让播放器加速到不可用档。
        player.setPlaybackSpeed(PlayerSpeedSetting.clamp(state.playerSpeed))
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
            val nowMs = System.currentTimeMillis()
            bufferMonitor.onSeekStarted(nowMs)
            lastSeekAtMs = nowMs
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
        val nowMs = System.currentTimeMillis()
        bufferMonitor.onSeekStarted(nowMs)
        lastSeekAtMs = nowMs
        val targetPosition = player.seekByOffset(deltaMs)
        val stepSeconds = (kotlin.math.abs(deltaMs) / 1_000L).coerceAtLeast(1L)
        val action = if (deltaMs < 0L) "快退 $stepSeconds 秒" else "快进 $stepSeconds 秒"
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
        val currentSpeed = PlayerSpeedSetting.clamp(latestState.playerSpeed)
        val nextSpeed = nextPlaybackSpeed(currentSpeed, PLAYER_SPEED_STEPS)
        player.setPlaybackSpeed(nextSpeed)
        actions.updatePlaybackSpeed(nextSpeed)
        seekGesturePrompt = "当前倍速 ${formatPlaybackSpeed(nextSpeed)}"
        seekGesturePromptNonce++
    }

    // 遥控键位统一到 UnifiedRemoteKeyRouter（替代旧 PlayerSeekKeyMap）：键码→动作由绑定表决定。
    val seekKeyMap = remember {
        UnifiedRemoteKeyRouter(
            mode = UnifiedRemoteKeyRouter.PlayerMode.Seek,
            callbacks = UnifiedRemoteKeyRouter.Callbacks(
                onAction = { false },
                chrome = chrome,
            ),
        )
    }
    seekKeyMap.callbacks = UnifiedRemoteKeyRouter.Callbacks(
        onAction = { action ->
            when (action) {
                is UnifiedRemoteKeyRouter.Action.SeekBy -> {
                    seekByRemote(action.deltaMs)
                    true
                }

                is UnifiedRemoteKeyRouter.Action.FinishSeek -> {
                    finishRemoteSeek()
                    true
                }

                is UnifiedRemoteKeyRouter.Action.TogglePlayback -> {
                    togglePlaybackByGesture()
                    true
                }

                is UnifiedRemoteKeyRouter.Action.Play -> {
                    session.play(player)
                    seekGesturePrompt = "播放"
                    seekGesturePromptNonce++
                    true
                }

                is UnifiedRemoteKeyRouter.Action.Pause -> {
                    bufferMonitor.onPaused()
                    session.pause(player)
                    seekGesturePrompt = "暂停"
                    seekGesturePromptNonce++
                    true
                }

                is UnifiedRemoteKeyRouter.Action.ChangeEpisode -> {
                    changeEpisodeByRemote(action.delta)
                    true
                }

                is UnifiedRemoteKeyRouter.Action.CycleSpeed -> {
                    cyclePlaybackSpeedByRemote()
                    true
                }

                is UnifiedRemoteKeyRouter.Action.OpenSettings -> {
                    chrome.openPanel(PlayerPanel.Settings)
                    true
                }

                is UnifiedRemoteKeyRouter.Action.OpenInfo -> {
                    chrome.openPanel(PlayerPanel.Info)
                    true
                }

                else -> false
            }
        },
        chrome = chrome,
    )

    // 8 个分项开关 + 灵敏度 + 边缘保护 + 手势锁：总开关关闭时全部手势失效。
    fun touchCapabilities(): PlayerTouchCapabilities = PlayerTouchCapabilities(
        seekDrag = gesturesEnabled && playbackSettings.gestureHorizontalSeekEnabled,
        doubleTapSeek = gesturesEnabled &&
            (playbackSettings.gestureDoubleTapSideEnabled || playbackSettings.gestureDoubleTapCenterEnabled),
        longPressSpeed = gesturesEnabled && playbackSettings.gestureLongPressSpeedEnabled,
        brightness = gesturesEnabled && playbackSettings.gestureLeftBrightnessEnabled,
        volume = gesturesEnabled && playbackSettings.gestureRightVolumeEnabled,
        lock = playbackSettings.gestureLockEnabled,
        verticalSwipeEnabled = gesturesEnabled &&
            (playbackSettings.gestureLeftBrightnessEnabled || playbackSettings.gestureRightVolumeEnabled),
        verticalSwipeLoose = false,
        leftBrightnessEnabled = playbackSettings.gestureLeftBrightnessEnabled,
        rightVolumeEnabled = playbackSettings.gestureRightVolumeEnabled,
        horizontalSeekEnabled = playbackSettings.gestureHorizontalSeekEnabled,
        doubleTapSideEnabled = playbackSettings.gestureDoubleTapSideEnabled,
        doubleTapCenterEnabled = playbackSettings.gestureDoubleTapCenterEnabled,
        longPressSpeedEnabled = playbackSettings.gestureLongPressSpeedEnabled,
        pinchZoomEnabled = gesturesEnabled && playbackSettings.gesturePinchZoomEnabled,
        topSwipeBackEnabled = gesturesEnabled && playbackSettings.gestureTopSwipeBackEnabled,
        sensitivity = playbackSettings.gestureSensitivity,
        edgeGuardEnabled = playbackSettings.gestureEdgeGuardEnabled,
        gestureLockEnabled = playbackSettings.gestureLockEnabled,
    )

    val touchController = remember {
        PlayerTouchGestureHandler(
            activity = activity,
            audioManager = audioManager,
            capabilities = touchCapabilities(),
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
            val nowMs = System.currentTimeMillis()
            bufferMonitor.onSeekStarted(nowMs)
            lastSeekAtMs = nowMs
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
            player.setPlaybackSpeed(PlayerSpeedSetting.clamp(longPressOriginalSpeed))
            seekGesturePrompt = null
        },
        onPinchZoom = { factor ->
            // 以画面比例档位承接捏合缩放：放大切“裁剪铺满”，缩小回“原始比例”；之间设冷却避免抖动。
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPinchSwitchAtMs >= 600L) {
                when {
                    factor > 1.12f && videoScaleMode != VideoScaleMode.Zoom -> {
                        actions.updateContentVideoScaleMode(videoScaleContentKey, VideoScaleMode.Zoom)
                        lastPinchSwitchAtMs = nowMs
                    }
                    factor < 0.89f && videoScaleMode != VideoScaleMode.Fit -> {
                        actions.updateContentVideoScaleMode(videoScaleContentKey, VideoScaleMode.Fit)
                        lastPinchSwitchAtMs = nowMs
                    }
                }
            }
        },
        onTopSwipeBack = { exitPlayer() },
    )
    touchController.capabilities = touchCapabilities()
    touchController.doubleTapSeekMs = playbackSettings.fastSeekStepSeconds.toLong() * 1_000L
    seekKeyMap.seekStepSeconds = playbackSettings.fastSeekStepSeconds
    seekKeyMap.bindings = state.appSettings.remoteKeyBindings
    DisposableEffect(player, activity, initialScreenBrightness) {
        onDispose {
            touchController.release()
            activity?.window?.restoreScreenBrightness(initialScreenBrightness)
        }
    }

    // 手机端退后台行为：pause 暂停；audio 继续出声；pip 尝试画中画，失败回退 audio。
    // 三种模式都不在此处 release：播放器只在离开播放页（组合销毁）时释放。
    DisposableEffect(lifecycleOwner, player, activity, isTelevision, backgroundPlaybackMode) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (!isTelevision && event == Lifecycle.Event.ON_STOP && player.isPlaying) {
                when (backgroundPlaybackMode) {
                    "audio" -> Unit
                    "pip" -> enterPictureInPictureSafely(activity)
                    else -> session.pause(player)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            posterUrl = movie.posterUrl,
            configure = { view ->
                // 电视模式或关闭播放器手势时不挂触摸监听：误触/触摸板不会意外改亮度、音量或进度。
                if (!isTelevision && gesturesEnabled) {
                    view.setOnTouchListener(touchController)
                } else {
                    view.setOnTouchListener(null)
                }
            },
        )
        // #262/#50：切集过渡遮罩叠在画面之上、控制条之下；只改 alpha，不动播放器实例。
        if (episodeFadeAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = episodeFadeAlpha * 0.92f)),
            )
        }
        // 片尾倒计时：明确告知用户将在几秒后切到下一集。
        if (autoNextCountdownRemaining > 0 && firstFrameFailure == null && !playbackFinished) {
            GesturePrompt(
                text = "即将播放下一集 · ${autoNextCountdownRemaining}s",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
            )
        }
        val failureKind = firstFrameFailure
        val overlayStatus = when {
            playbackFinished -> PlaybackStatus.Finished(
                title = "已播完",
                message = "${movie.name} 已播放完毕，可返回详情页。",
            )
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
                autoReconnectTried = 0
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
            onFinishedBack = { exitPlayer() },
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
        // 播放进度每 250ms 变化一次，但下列派生数据只随影片/剧集变化。
        // 缓存引用，避免每次重组都重建列表导致面板整棵树无意义比较。
        val chromeEpisodeTitles = remember(source) { source.episodes.map { it.title } }
        val chromeSources = remember(movie) {
            movie.playSources.map { PlayerChromeSource(name = it.name, lineId = it.lineId) }
        }
        val chromeSourceEnabled = remember(movie, currentEpisodeTitle, currentEpisodeIndex) {
            movie.playSources.map { candidate ->
                correspondingEpisodeIndex(candidate, currentEpisodeTitle, currentEpisodeIndex) != null
            }
        }
        val chromePlaybackUrls = remember(source, currentEpisodeIndex) {
            listOf(
                PlayerChromeUrl(
                    label = "实际播放地址（点播线路）",
                    url = source.episodes[currentEpisodeIndex].url,
                ),
            )
        }
        PlayerChrome(
            state = chrome,
            data = PlayerChromeData(
                title = movie.name,
                episodes = chromeEpisodeTitles,
                sources = chromeSources,
                episodeIndex = currentEpisodeIndex,
                sourceIndex = state.playerSourceIndex,
                sourceEnabled = chromeSourceEnabled,
                positionMs = uiPosition,
                durationMs = uiDuration,
                bufferedMs = playbackHost.progress.bufferedMs,
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
                playbackUrls = chromePlaybackUrls,
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
                castActive = castingDevice != null,
                lock = playbackSettings.gestureLockEnabled,
            ),
            hiddenControls = state.appSettings.playerHiddenControls,
            actions = PlayerChromeActions(
                onToggle = { togglePlaybackByGesture() },
                onSeek = { position ->
                    val nowMs = System.currentTimeMillis()
                    bufferMonitor.onSeekStarted(nowMs)
                    lastSeekAtMs = nowMs
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
                    val clamped = PlayerSpeedSetting.clamp(speed)
                    player.setPlaybackSpeed(clamped)
                    actions.updatePlaybackSpeed(clamped)
                },
                onSelectResize = { mode -> actions.updateContentVideoScaleMode(videoScaleContentKey, mode) },
                onSelectAudioTrack = { player.selectAudioTrack(it) },
                onSelectSubtitleTrack = { trackId ->
                    player.selectSubtitleTrack(trackId)
                    actions.updateContentSubtitleTrack(videoScaleContentKey, trackId)
                },
                onBack = { exitPlayer() },
                onCast = {
                    if (castingDevice != null) stopCasting() else castDialogVisible = true
                },
                onLockChange = { locked ->
                    if (playbackSettings.gestureLockEnabled) {
                        chrome.interaction++
                        if (locked) {
                            touchController.cancelPendingGestures()
                        }
                        seekGesturePrompt = if (locked) "播放已锁定" else "已解锁"
                        seekGesturePromptNonce++
                    }
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
                textColor = if (playbackIssue != null) TvColors.Warning else TvColors.Accent,
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
                            Text(message, color = TvColors.Danger)
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
    textColor: Color = TvColors.Accent,
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
 * 尽力进入画中画：API 26+、系统声明支持 PiP 时才尝试。
 * 失败（例如 Manifest 未声明 supportsPictureInPicture）不抛异常，退后台继续音频播放。
 */
private fun enterPictureInPictureSafely(activity: android.app.Activity?): Boolean {
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
    return runCatching {
        activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        true
    }.getOrDefault(false)
}

/**
 * 重建播放器（切换解码策略）时的续播种子。
 * 旧播放器离开组合时记录一次，新播放器建源后立即消费，避免影响后续换源/换集。
 */
private class PlaybackRebuildSeed {
    private var episodeIndex: Int = -1
    private var positionMs: Long = 0L

    fun remember(player: ExoPlayer) {
        // 只要已定位到某一集（含位置 0：刚开播就切换解码策略）就记录续播种子，
        // 否则新播放器会从头开始而丢失"第几集"的上下文。
        if (player.currentMediaItemIndex >= 0) {
            episodeIndex = player.currentMediaItemIndex
            positionMs = player.currentPosition.coerceAtLeast(0L)
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
// #262/#50：切集遮罩淡入/淡出时长与完全遮盖的保持时长（毫秒）。
private const val EPISODE_FADE_DURATION_MS = 180
private const val EPISODE_FADE_HOLD_MS = 220L

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
