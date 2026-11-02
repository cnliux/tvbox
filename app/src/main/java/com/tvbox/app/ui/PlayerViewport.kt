package com.tvbox.app.ui

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
    VideoScaleMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoScaleMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

/** 系统栏巡检间隔：兼顾"旋转后尽快恢复全屏"与"手势临时唤出时不被立刻打断"。 */
private const val IMMERSIVE_REHIDE_INTERVAL_MS = 3_000L

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
        // ON_RESUME 只在切后台返回时触发；旋转屏幕、下拉通知栏、三方弹窗关闭后系统栏都会重新出现，
        // 手机版播放页需要轻量巡检把它重新隐藏，否则底部导航栏会压住播放控件。
        val rehide = object : Runnable {
            override fun run() {
                val visibleNow = ViewCompat.getRootWindowInsets(decor)?.let {
                    it.isVisible(statusBars) || it.isVisible(navigationBars)
                } ?: false
                if (visibleNow) hideBars()
                decor.postDelayed(this, IMMERSIVE_REHIDE_INTERVAL_MS)
            }
        }
        decor.postDelayed(rehide, IMMERSIVE_REHIDE_INTERVAL_MS)
        onDispose {
            decor.removeCallbacks(rehide)
            owner.lifecycle.removeObserver(observer)
            controller.systemBarsBehavior = previousBehavior
            if (statusWasVisible) controller.show(statusBars) else controller.hide(statusBars)
            if (navigationWasVisible) controller.show(navigationBars) else controller.hide(navigationBars)
        }
    }
}
