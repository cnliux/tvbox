package com.tvbox.app.ui

import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.tvbox.app.util.logSafeText
import com.tvbox.app.domain.DecoderStrategy
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * 安全释放 ExoPlayer：先 pause + removeListener，再 release。
 * 防止 release() 后仍有 Listener 回调触发 NPE。
 */
internal fun safeReleasePlayer(player: ExoPlayer) {
    try {
        ManagedPlayerProbes.remove(player)?.let { player.removeAnalyticsListener(it) }
        if (player.isPlaying) player.pause()
        // ExoPlayer 不支持并发 release，确保在调用线程执行
        player.release()
    } catch (e: Exception) {
        android.util.Log.w("TVBoxPlayer", "safeReleasePlayer failed: ${logSafeText(e.message)}")
    }
}

/**
 * 安全 prepare：若 player 已被 release 则静默忽略。
 * 返回 false 表示 prepare 失败（已打印日志），调用方应降级到错误页。
 */
internal fun safePrepare(
    player: ExoPlayer,
    mediaItem: MediaItem,
    playWhenReady: Boolean = true,
): Boolean {
    return try {
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
        true
    } catch (e: Exception) {
        android.util.Log.e("TVBoxPlayer", "safePrepare failed: ${logSafeText(e.message)}")
        false
    }
}

/**
 * 共享播放器工厂：四个播放页只通过这里配置 Renderers，避免各页各建一套 factory。
 *
 * 始终开启 [DefaultRenderersFactory.setEnableDecoderFallback]（默认「自适应」档的解码失败回落）；
 * [DecoderStrategy.HardwareOnly] 时再用 [MediaCodecSelector] 只保留硬件解码器，
 * [DecoderStrategy.SoftwareOnly] 时只保留软件解码器。两者都基于系统 MediaCodec 集合。
 *
 * FFmpeg 软解码（nextlib-media3ext，GPL-3.0）：
 * - [DecoderStrategy.SoftwareOnly] 用 [NextRenderersFactory] + FFmpeg 优先模式：FFmpeg 解码器
 *   排在系统软解之前，硬件解码异常或黑屏时切到软解更可靠（覆盖 H.264/HEVC/VP9/AV1 等）。
 * - [DecoderStrategy.Auto] 用 FFmpeg 兜底模式（EXTENSION_RENDERER_MODE_ON）：系统硬解/软解都不支持的
 *   编码由 FFmpeg 兜底，不影响默认「硬解优先」语义。
 * - [DecoderStrategy.HardwareOnly] 保持纯 MediaCodec 硬件选择器，不引入 FFmpeg renderer。
 */
@OptIn(UnstableApi::class)
internal fun buildManagedExoPlayer(
    context: Context,
    decoderStrategy: DecoderStrategy,
    /** 缓冲目标（毫秒）：流畅 8s / 均衡 15s / 省流 25s。 */
    bufferTargetMs: Long = DEFAULT_BUFFER_TARGET_MS,
    /** 音频输出：auto / stereo / passthrough。 */
    audioOutputMode: String = "auto",
    configure: (ExoPlayer.Builder) -> ExoPlayer.Builder = { it },
): ExoPlayer {
    val useFfmpeg = decoderStrategy != DecoderStrategy.HardwareOnly
    val baseSelector = when (decoderStrategy) {
        DecoderStrategy.HardwareOnly -> hardwareOnlyMediaCodecSelector()
        DecoderStrategy.SoftwareOnly -> softwareOnlyMediaCodecSelector()
        DecoderStrategy.Auto -> MediaCodecSelector.DEFAULT
    }
    // SoftwareOnly 时 FFmpeg 解码器优先；Auto 时 FFmpeg 仅兜底系统解码器不支持的编码。
    val extensionMode = when (decoderStrategy) {
        DecoderStrategy.SoftwareOnly -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    }
    val renderersFactory = if (useFfmpeg) {
        NextRenderersFactory(context)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(resolveAudioOutputSelector(context, baseSelector, audioOutputMode))
    } else {
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(resolveAudioOutputSelector(context, baseSelector, audioOutputMode))
    }
    return configure(
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(buildBufferLoadControl(bufferTargetMs)),
    )
        .build()
        .apply { playWhenReady = true }
        .also { player ->
            // 解码器初始化可能早于页面级诊断监听挂载（直播切台时尤甚）：构建即挂探针。
            val probe = ManagedPlayerProbe()
            player.addAnalyticsListener(probe)
            ManagedPlayerProbes.put(player, probe)
        }
}

internal const val DEFAULT_BUFFER_TARGET_MS = 15_000L

/**
 * 按缓冲目标生成 LoadControl。只调时长阈值，不引入自定义 LoadControl 子类；
 * 起播/重缓冲阈值始终不超过最小缓冲，满足 Media3 的约束。
 */
@OptIn(UnstableApi::class)
private fun buildBufferLoadControl(bufferTargetMs: Long): DefaultLoadControl {
    val target = bufferTargetMs.coerceIn(3_000L, 60_000L).toInt()
    val minBufferMs = (target * 0.5).toInt().coerceAtLeast(1_000)
    val playbackStartMs = minOf(2_500, (target * 0.25).toInt()).coerceIn(500, minBufferMs)
    val rebufferMs = minOf(5_000, (target * 0.4).toInt()).coerceIn(1_000, minBufferMs)
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(minBufferMs, target, playbackStartMs, rebufferMs)
        .build()
}

/** 常见「只能直通」的编码；stereo 档过滤这些编码，强制播放器选择可解码为 PCM 的音轨。 */
private val PASSTHROUGH_ONLY_MIMES = setOf(
    "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/dts", "audio/dtshd", "audio/truehd",
    "audio/vnd.dts", "audio/vnd.dts.hd",
)

/**
 * 音频输出档位落到解码器选择上：
 * - stereo：禁用直通编码，避免外部功放把多声道直通出去导致手机无声；
 * - passthrough：仅在设备支持时保持默认（允许直通），否则回退 auto 的安全路径；
 * - auto：原样保留解码策略选择器。
 *
 * 不增加任何新依赖；音频输出档位只影响 MediaCodec 选择器，不涉及 FFmpeg renderer 的选择。
 */
@OptIn(UnstableApi::class)
private fun resolveAudioOutputSelector(
    context: Context,
    base: MediaCodecSelector,
    audioOutputMode: String,
): MediaCodecSelector {
    val disablePassthrough = when (audioOutputMode) {
        "stereo" -> true
        "passthrough" -> !deviceSupportsAudioPassthrough(context)
        else -> false
    }
    if (!disablePassthrough) return base
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneling ->
        if (MimeTypes.isAudio(mimeType) && PASSTHROUGH_ONLY_MIMES.any { mimeType.equals(it, ignoreCase = true) }) {
            emptyList()
        } else {
            base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneling)
        }
    }
}

/**
 * 直通能力探测（启发式）：优先看 HDMI 音频是否已插入，其次蓝牙 A2DP，最后电视特征。
 * Android 没有公开的「是否支持 AC3 直通」API，探测失败按不支持处理并回退 auto。
 */
private fun deviceSupportsAudioPassthrough(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
    return runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val hdmiPlugged = context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            ?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1
        hdmiPlugged || manager.isBluetoothA2dpOn ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }.getOrDefault(false)
}

/**
 * 播放器构建期探针：保留最近一次已初始化的解码器名、视频格式与首帧耗时。
 * 页面监听晚到或切台复用解码器时，[PlayerDiagnostics] 用这里的数据回填。
 */
@OptIn(UnstableApi::class)
internal class ManagedPlayerProbe : AnalyticsListener {
    @Volatile
    var decoderName: String? = null
        private set

    @Volatile
    var videoFormat: Format? = null
        private set

    @Volatile
    var firstFrameMs: Long = -1L
        private set

    private var firstFrameStartMs: Long? = null

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializationDurationMs: Long,
        initializedTimestampMs: Long,
    ) {
        this.decoderName = decoderName
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        videoFormat = format
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        if (firstFrameMs < 0L) {
            val start = firstFrameStartMs ?: eventTime.realtimeMs
            firstFrameMs = (eventTime.realtimeMs - start).coerceAtLeast(0L)
        }
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // 切台/换集重新计时；不清空 decoderName：解码器复用时不会再回调初始化。
        firstFrameStartMs = eventTime.realtimeMs
        firstFrameMs = -1L
    }
}

/** 探针注册表：播放器销毁时移除，避免页面监听晚于初始化而拿不到数据。 */
internal object ManagedPlayerProbes {
    private val probes = java.util.WeakHashMap<ExoPlayer, ManagedPlayerProbe>()

    @Synchronized
    fun put(player: ExoPlayer, probe: ManagedPlayerProbe) {
        probes[player] = probe
    }

    @Synchronized
    fun get(player: ExoPlayer): ManagedPlayerProbe? = probes[player]

    @Synchronized
    fun remove(player: ExoPlayer): ManagedPlayerProbe? = probes.remove(player)
}

/** 发生回落的轨道类型：视频/音频分别记录，信息面板据此分别标注。 */
internal enum class DecoderTrackKind { Video, Audio }

/** 回落方向：强制硬解缺硬解、强制软解缺软解。 */
internal enum class DecoderFallbackDirection { Hardware, Software }

/** 「强制硬解/软解」档回落到可用解码器的一次性事件；只含方向、编码类型与轨道类型，不含地址。 */
internal data class HardwareDecoderFallbackEvent(
    val direction: DecoderFallbackDirection,
    val mimeType: String,
    val kind: DecoderTrackKind,
    val sequence: Long,
)

/**
 * 强制硬解回落的全局通知：选择器在播放线程触发，播放页在主线程消费。
 * - [kinds] 记录本次构建中实际发生回落的轨道（视频/音频可同时存在）；
 * - [event] 只在每次构建的首条回落时更新，保证 Toast 只弹一次；
 * - [reset] 在每次构建选择器前清空，避免上一台的状态串到新播放器。
 */
internal object HardwareDecoderFallbackNotice {
    // report() 来自 MediaCodecSelector 的解码线程，event/kinds 又被 UI 组合直接读取：
    // 读写必须同一把锁，否则「先判断再写入」的复合操作会丢更新/读到半新半旧快照。
    // 对外仍以 mutableStateOf 作为可观察载体，Compose 侧 API（直接读 event/kinds）保持不变。
    private val lock = Any()

    private var stateEvent: HardwareDecoderFallbackEvent? by mutableStateOf(null)
    private var stateKinds: Set<DecoderTrackKind> by mutableStateOf(emptySet())

    private var sequence = 0L

    val event: HardwareDecoderFallbackEvent?
        get() = synchronized(lock) { stateEvent }

    val kinds: Set<DecoderTrackKind>
        get() = synchronized(lock) { stateKinds }

    fun reset() {
        synchronized(lock) {
            stateKinds = emptySet()
            stateEvent = null
            // 不清零 sequence：Toast 去重靠序号递增，重置事件不能让上一条提示重复弹出。
        }
    }

    fun report(mimeType: String, kind: DecoderTrackKind, direction: DecoderFallbackDirection) {
        synchronized(lock) {
            if (kind !in stateKinds) stateKinds = stateKinds + kind
            if (stateEvent == null) {
                sequence += 1
                stateEvent = HardwareDecoderFallbackEvent(direction, mimeType, kind, sequence)
            }
        }
    }
}

/**
 * 强制硬解：只返回 `hardwareAccelerated` 的解码器。
 * 设备确实没有对应硬解时回退默认候选，避免选择器返回空列表导致取流直接失败；
 * 但回落必须可见：按编码去重记录脱敏日志，并通过 [HardwareDecoderFallbackNotice]
 * 让播放页提示，同时区分是视频轨还是音频轨回落。
 */
@OptIn(UnstableApi::class)
private fun hardwareOnlyMediaCodecSelector(): MediaCodecSelector {
    // 同一次构建里音频/视频可能各回落一次；按编码去重，但两类轨道都要上报给信息面板。
    val reportedMimes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    HardwareDecoderFallbackNotice.reset()
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneling)
        val hardware = all.filter { it.hardwareAccelerated }
        if (hardware.isNotEmpty()) {
            hardware
        } else {
            if (reportedMimes.add(mimeType)) {
                val kind = if (androidx.media3.common.MimeTypes.isAudio(mimeType)) {
                    DecoderTrackKind.Audio
                } else {
                    DecoderTrackKind.Video
                }
                android.util.Log.w("TVBoxPlayer", "强制硬解回落：$mimeType 无可用硬件解码器（${kind.name}）")
                HardwareDecoderFallbackNotice.report(mimeType, kind, DecoderFallbackDirection.Hardware)
            }
            all
        }
    }
}

/**
 * 强制软解：只返回非 `hardwareAccelerated` 的解码器（系统自带软件编解码，如 omx.google / c2.android）。
 * 设备确实没有对应软解时回退默认候选，避免选择器返回空列表导致取流直接失败；
 * 回落同样通过 [HardwareDecoderFallbackNotice] 记录，让播放页提示「无可用软解，已回退」。
 */
@OptIn(UnstableApi::class)
private fun softwareOnlyMediaCodecSelector(): MediaCodecSelector {
    val reportedMimes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    HardwareDecoderFallbackNotice.reset()
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneling)
        val software = all.filter { !it.hardwareAccelerated }
        if (software.isNotEmpty()) {
            software
        } else {
            if (reportedMimes.add(mimeType)) {
                val kind = if (androidx.media3.common.MimeTypes.isAudio(mimeType)) {
                    DecoderTrackKind.Audio
                } else {
                    DecoderTrackKind.Video
                }
                android.util.Log.w("TVBoxPlayer", "强制软解回落：$mimeType 无可用软件解码器（${kind.name}）")
                HardwareDecoderFallbackNotice.report(mimeType, kind, DecoderFallbackDirection.Software)
            }
            all
        }
    }
}

/** 四个播放页共用的一次性提示：策略与实际不一致时不静默。 */
@Composable
internal fun HardwareDecoderFallbackToast() {
    val context = LocalContext.current.applicationContext
    val event = HardwareDecoderFallbackNotice.event
    var lastSequence by remember { mutableLongStateOf(event?.sequence ?: 0L) }
    LaunchedEffect(event?.sequence) {
        val current = event ?: return@LaunchedEffect
        if (current.sequence > lastSequence) {
            lastSequence = current.sequence
            val message = when (current.direction) {
                DecoderFallbackDirection.Hardware -> "该编码无可用硬件解码器，已回落到软解"
                DecoderFallbackDirection.Software -> "该编码无可用软件解码器，已回退"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * 统一的播放器持有：进入组合时创建，离开组合时安全释放。
 * [decoderStrategy] 变化会重建播放器（解码器只在构建时选择），页面需按重建语义续播/重连。
 */
@Composable
internal fun rememberManagedExoPlayer(
    decoderStrategy: DecoderStrategy = DecoderStrategy.Auto,
    bufferTargetMs: Long = DEFAULT_BUFFER_TARGET_MS,
    audioOutputMode: String = "auto",
): ExoPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember(decoderStrategy, bufferTargetMs, audioOutputMode) {
        buildManagedExoPlayer(context, decoderStrategy, bufferTargetMs, audioOutputMode)
    }
    DisposableEffect(player) {
        onDispose { safeReleasePlayer(player) }
    }
    return player
}

/**
 * 同 [rememberManagedExoPlayer]，但允许当前没有可播放内容（工厂返回 null 时不创建播放器）。
 * [keys] 变化时旧播放器会先被安全释放，再创建新实例。
 */
@Composable
internal fun rememberManagedExoPlayerOrNull(
    vararg keys: Any?,
    factory: (android.content.Context) -> ExoPlayer?,
): ExoPlayer? {
    val context = LocalContext.current.applicationContext
    val player = remember(*keys) { factory(context) }
    DisposableEffect(player) {
        onDispose { player?.let(::safeReleasePlayer) }
    }
    return player
}
