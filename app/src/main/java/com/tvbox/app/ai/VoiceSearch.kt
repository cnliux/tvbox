package com.tvbox.app.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import java.util.Locale

/**
 * 语音找片：走系统语音识别页（ACTION_RECOGNIZE_SPEECH），
 * 由系统应用持有麦克风权限，本应用不申请 RECORD_AUDIO、不做运行时权限请求。
 *
 * 接入方式：
 * ```
 * val request = rememberVoiceSearchRequest { text -> /* 填入输入框并提交 AI 检索 */ }
 * val intent = VoiceSearch.createIntent(context)
 * if (intent != null) launcher.launch(intent) else /* 提示设备无语音识别 */
 * ```
 * 也可直接用 [rememberVoiceSearchRequest]（定义在 AiSearchScreen.kt）统一处理。
 */
object VoiceSearch {

    /** 创建系统语音识别 Intent；设备上没有可处理的 Activity 时返回 null，调用方需降级。 */
    fun createIntent(context: Context): Intent? {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, PROMPT)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        return intent.takeIf { it.canBeHandled(context) }
    }

    /** 从系统语音页返回的结果里取第一条非空文本；取消或无结果时返回 null。 */
    fun parseResult(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun Intent.canBeHandled(context: Context): Boolean =
        context.packageManager.resolveActivity(this, PackageManager.MATCH_DEFAULT_ONLY) != null

    private const val PROMPT = "说片名、类型或剧情，例如：关于时间旅行的电影"
}
