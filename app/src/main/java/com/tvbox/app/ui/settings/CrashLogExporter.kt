package com.tvbox.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tvbox.app.crash.CrashHandler
import java.io.File

/**
 * 导出崩溃日志（#226）：合并本地崩溃日志为单个文本，通过系统分享发送（用户可选目标）。
 * 无日志时返回提示消息；成功返回 null（已拉起分享）。
 */
fun exportCrashLogs(context: Context): String? {
    val logs = CrashHandler.getPendingCrashLogs()
    if (logs.isEmpty()) return "暂无崩溃日志"
    val dir = File(context.cacheDir, "crash_export").apply { mkdirs() }
    val out = File(dir, "tvbox_crash_logs.txt")
    val builder = StringBuilder()
    logs.forEach { file ->
        runCatching { builder.append(file.readText()) }
            .onSuccess { builder.append("\n\n") }
    }
    if (builder.isEmpty()) return "日志为空"
    out.writeText(builder.toString())
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
