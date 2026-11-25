package com.tvbox.app.util

import android.util.Log
import com.tvbox.app.BuildConfig

/**
 * S1-07：统一日志入口。Release 保留 Log 供盒子排障，但增加两道闸：
 *
 * 1. **惰性拼接**：[msg] 是 lambda，Debug=false 时 [d] 连字符串都不会构造
 *    （`inline` + `BuildConfig.DEBUG` 常量折叠，release 下调用被整体消除）；
 * 2. **强制脱敏**：[w] / [e] 默认过 [CrashLogRedactor.redact]，
 *    避免 `error.message` 里带签名 URL / Bearer / API Key 被任何 `adb logcat` 读到。
 *
 * 用法：`TLog.d("Tag") { "count=$n" }` / `TLog.w("Tag") { "失败：${error.message}" }`。
 * `app/src/main` 内禁止直接调用 `Log.`（CI 的 `check_direct_log.py` 门禁）。
 */
object TLog {
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg())
    }

    inline fun i(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg())
    }

    inline fun w(tag: String, redact: Boolean = true, msg: () -> String) {
        val text = msg()
        Log.w(tag, if (redact) CrashLogRedactor.redact(text) else text)
    }

    /** T3-20：带 Throwable 的 warn（裸 `Log.w(tag, msg, error)` 的替代）。 */
    inline fun w(tag: String, throwable: Throwable, redact: Boolean = true, msg: () -> String) {
        val text = msg()
        Log.w(tag, if (redact) CrashLogRedactor.redact(text) else text, throwable)
    }

    inline fun e(tag: String, redact: Boolean = true, msg: () -> String) {
        val text = msg()
        Log.e(tag, if (redact) CrashLogRedactor.redact(text) else text)
    }

    inline fun e(tag: String, throwable: Throwable, msg: () -> String) {
        Log.e(tag, CrashLogRedactor.redact(msg()), throwable)
    }
}
