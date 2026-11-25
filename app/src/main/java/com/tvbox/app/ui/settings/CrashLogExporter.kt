package com.tvbox.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tvbox.app.crash.CrashHandler
import com.tvbox.app.domain.GlobalPlayState
import com.tvbox.app.util.CrashLogRedactor
import java.io.File

/**
 * 导出崩溃日志（#226）：合并本地崩溃日志为单个文本，通过系统分享发送（用户可选目标）。
 * 无日志时返回提示消息；成功返回 null（已拉起分享）。
 * T3-23：导出时附带面包屑快照。
 */
fun exportCrashLogs(context: Context): String? {
    val logs = CrashHandler.getPendingCrashLogs()
    if (logs.isEmpty()) return "暂无崩溃日志"
    // S1-03：改落 App 私有外部目录（其它 App 默认不可见，卸载即没），不再写公共 Downloads。
    val privateRoot = context.getExternalFilesDir(null)
        ?: return "外部存储不可用，无法导出崩溃日志"
    val dir = File(privateRoot, "crash-reports").apply { mkdirs() }
    val out = File(dir, "tvbox_crash_logs.txt")
    val builder = StringBuilder()
    logs.forEach { file ->
        runCatching { builder.append(file.readText()) }
            .onSuccess { builder.append("\n\n") }
    }
    // T3-23：追加面包屑快照
    builder.append("\n\n--- 面包屑记录（最近64帧）---\n")
    builder.append(GlobalPlayState.breadcrumb.render())
    if (builder.isEmpty()) return "日志为空"
    // 签名参数 / Bearer / API Key 一律遮盖后再落盘，分享出去的这份同样已脱敏。
    out.writeText(CrashLogRedactor.redactAll(builder.toString()))
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    } catch (error: Exception) {
        return "无法读取日志文件"
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "TVBox 崩溃日志")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(send, "导出崩溃日志"))
        null
    }.getOrElse { "无可用分享目标" }
}
