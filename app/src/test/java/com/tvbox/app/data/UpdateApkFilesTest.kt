package com.tvbox.app.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * P2-17 / S1-02 安装包校验「缺 sha256 即失败」的回归锁定：
 * 原来 sha256 为空时跳过校验，攻击者只需让 update.json 先返回旧格式即可绕过；
 * 现在空 sha256 一律按失败处理，杜绝「有 URL 无校验和」的静默拒更/绕过。
 */
class UpdateApkFilesTest {

    private fun tempApk(): File {
        val file = File(
            System.getProperty("java.io.tmpdir"),
            "update_apk_test_${System.nanoTime()}.apk",
        )
        file.writeBytes(ByteArray(1024) { (it % 251).toByte() })
        return file
    }

    @Test
    fun missingSha256FailsClosed() {
        val apk = tempApk()
        try {
            val err = assertThrows(IOException::class.java) {
                verifyUpdateApk(apk, apk.length(), sha256 = "")
            }
            assertTrue(err.message!!.contains("缺少校验信息"))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun correctSha256Passes() {
        val apk = tempApk()
        try {
            val hash = updateApkSha256(apk)
            verifyUpdateApk(apk, apk.length(), sha256 = hash) // 不应抛出
        } finally {
            apk.delete()
        }
    }

    @Test
    fun wrongSha256Fails() {
        val apk = tempApk()
        try {
            assertThrows(IOException::class.java) {
                verifyUpdateApk(apk, apk.length(), sha256 = "deadbeef")
            }
        } finally {
            apk.delete()
        }
    }

    @Test
    fun truncatedSizeFails() {
        val apk = tempApk()
        try {
            assertThrows(IOException::class.java) {
                verifyUpdateApk(apk, size = apk.length() + 1, sha256 = updateApkSha256(apk))
            }
        } finally {
            apk.delete()
        }
    }
}
