package com.tvbox.app.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal fun updateApkSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun verifyUpdateApk(file: File, size: Long, sha256: String) {
    if (!file.isFile || file.length() == 0L || (size > 0 && file.length() != size)) {
        throw IOException("安装包下载不完整，请重新下载")
    }
    // P2-17 / S1-02：缺失 sha256 按「失败」处理而非跳过。否则攻击者只需让
    // update.json 那一路先返回旧格式（无 sha256 字段），校验整段被跳过。
    if (sha256.isBlank()) {
        throw IOException("安装包缺少校验信息（sha256），已拒绝安装")
    }
    if (!updateApkSha256(file).equals(sha256, true)) {
        throw IOException("安装包校验失败，请重新下载")
    }
}
