package com.tvbox.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** T8-52：释放工程收尾入口的可执行性门禁。 */
class ReleaseCutTest {

    private val justfile: String by lazy { TestProjectFiles.read("justfile") }

    @Test
    fun `release-cut recipe exists and runs the automated gates`() {
        assertTrue("缺少 release-cut 目标", Regex("""^release-cut\s+V:""", RegexOption.MULTILINE).containsMatchIn(justfile))
        val body = justfile.substringAfter("release-cut V:").substringBefore("\n\n")
        assertTrue(body, "set -e" in body)
        assertTrue(body, "./tools/doctor.sh" in body)
        assertTrue(body, "testDebugUnitTest" in body)
        assertTrue(body, "just smoke" in body)
        assertTrue(body, "just audit" in body)
        assertTrue(body, "{{V}}" in body)
    }

    @Test
    fun `release-cut prints the two manual steps instead of pretending to automate them`() {
        val body = justfile.substringAfter("release-cut V:")
        assertTrue(body, "还剩 2 步" in body)
        assertTrue(body, "tools/fixtures/" in body)
        assertTrue(body, "签名指纹" in body)
        assertTrue(body, "已知问题" in body)
    }
}
