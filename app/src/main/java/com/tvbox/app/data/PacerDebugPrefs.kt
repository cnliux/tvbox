package com.tvbox.app.data

import android.content.Context
import platformlive.Platformlive

/**
 * 「一起看」pacer 目标缓冲的调试开关持久化。
 *
 * 仅用于真机调试：把用户选中的缓冲档位存到独立 SharedPreferences，
 * 应用启动（直播解析器初始化）后回写到 Go 全局变量，使配置在下次代理启动时生效；
 * 由于 Go 侧每帧都读取该值，调试过程中点按按钮也能即时对所有在播代理生效。
 *
 * 默认 5000ms（与 Go 侧 flvProxyPacerTargetBufferMs 初始值一致，对应 4~6s 目标缓冲）。
 */
object PacerDebugPrefs {
    private const val PREF_NAME = "tvbox.live.debug"
    private const val KEY_PACER_BUFFER_MS = "pacer_target_buffer_ms"
    private const val DEFAULT_MS = 5000L

    /** 可选档位（毫秒）：2s / 5s / 8s / 关闭（严格实时）。循环顺序即此数组顺序。 */
    val PRESETS: LongArray = longArrayOf(2000L, 5000L, 8000L, 0L)

    fun loadPacerBufferMs(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_PACER_BUFFER_MS, DEFAULT_MS)
        return if (saved in PRESETS) saved else DEFAULT_MS
    }

    fun savePacerBufferMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PACER_BUFFER_MS, ms.coerceAtLeast(0L))
            .apply()
    }

    /** 应用已保存值到 Go 全局变量；应在 Platformlive.init 之后调用。 */
    fun applyToBridge(context: Context) {
        runCatching { Platformlive.setPacerTargetBufferMs(loadPacerBufferMs(context)) }
    }

    /** 给定当前档位，返回循环后的下一档位。 */
    fun nextPreset(currentMs: Long): Long {
        val idx = PRESETS.indexOf(currentMs)
        return PRESETS[(if (idx < 0) 0 else idx + 1) % PRESETS.size]
    }
}
