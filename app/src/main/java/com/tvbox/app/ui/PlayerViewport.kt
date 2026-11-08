package com.tvbox.app.ui

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.tvbox.app.domain.VideoScaleMode

@androidx.annotation.OptIn(UnstableApi::class)
internal fun VideoScaleMode.toPlayerResizeMode(): Int = when (this) {
    VideoScaleMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    // 固定比例档位：内容拉伸填满强制比例框，真正改变画面缩放而非只改容器。
    VideoScaleMode.Ratio16_9,
    VideoScaleMode.Ratio4_3,
    VideoScaleMode.Ratio1_1,
    VideoScaleMode.Ratio21_9,
    VideoScaleMode.Ratio9_16,
    -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    VideoScaleMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoScaleMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

/** 系统栏巡检间隔：兼顾"旋转后尽快恢复全屏"与"手势临时唤出时不被立刻打断"。 */
private const val IMMERSIVE_REHIDE_INTERVAL_MS = 3_000L

/** 系统栏显隐动画结束后至少等待这么久再巡检，避免用户刚下拉通知栏就被抢回全屏。 */
private const val IMMERSIVE_REHIDE_GRACE_MS = 1_500L

/** Only the on-demand playback page owns immersive mode; restore it on exit. */
@Suppress("DEPRECATION")
@Composable
internal fun FullscreenWhilePlayingPageVisible(activity: Activity?) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(activity, owner) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose { }
        val decor = window.decorView
        val controller = WindowCompat.getInsetsController(window, decor)
        val insets = ViewCompat.getRootWindowInsets(decor)
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val navigationBars = WindowInsetsCompat.Type.navigationBars()
        val statusWasVisible = insets?.isVisible(statusBars)
            ?: (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0)
        val navigationWasVisible = insets?.isVisible(navigationBars)
            ?: (decor.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0)
        val previousBehavior = controller.systemBarsBehavior
        fun hideBars() {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(statusBars or navigationBars)
        }
        hideBars()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hideBars()
        }
        owner.lifecycle.addObserver(observer)
        // 记录系统栏动画结束时间：用户主动下拉通知栏/上滑导航后，给一段宽限期再巡检，
        // 避免"刚拉开就被重新隐藏"的打架现象。
        var lastBarsAnimationEndMs = 0L
        val animationCallback = object : WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP,
        ) {
            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>,
            ): WindowInsetsCompat = insets

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                lastBarsAnimationEndMs = SystemClock.elapsedRealtime()
            }
        }
        ViewCompat.setWindowInsetsAnimationCallback(decor, animationCallback)
        // ON_RESUME 只在切后台返回时触发；旋转屏幕、三方弹窗关闭后系统栏都会重新出现，
        // 手机版播放页需要轻量巡检把导航栏重新隐藏，否则底部导航栏会压住播放控件。
        // 只检查导航栏：下拉通知栏通常只露出状态栏，此时不应抢回，以免打断通知查看。
        val rehide = object : Runnable {
            override fun run() {
                val navigationVisibleNow = ViewCompat.getRootWindowInsets(decor)
                    ?.isVisible(navigationBars) ?: false
                val recentlyAnimated =
                    SystemClock.elapsedRealtime() - lastBarsAnimationEndMs < IMMERSIVE_REHIDE_GRACE_MS
                if (navigationVisibleNow && !recentlyAnimated) hideBars()
                decor.postDelayed(this, IMMERSIVE_REHIDE_INTERVAL_MS)
            }
        }
        decor.postDelayed(rehide, IMMERSIVE_REHIDE_INTERVAL_MS)
        onDispose {
            decor.removeCallbacks(rehide)
            ViewCompat.setWindowInsetsAnimationCallback(decor, null)
            owner.lifecycle.removeObserver(observer)
            controller.systemBarsBehavior = previousBehavior
            if (statusWasVisible) controller.show(statusBars) else controller.hide(statusBars)
            if (navigationWasVisible) controller.show(navigationBars) else controller.hide(navigationBars)
        }
    }
}
