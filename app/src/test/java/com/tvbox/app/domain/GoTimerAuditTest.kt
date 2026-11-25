package com.tvbox.app.domain

import com.tvbox.app.TestProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5-36：Go 循环内 time.After / defer 静态门禁的 JVM 单测。 */
class GoTimerAuditTest {

    @Test
    fun `detects time after inside a for loop`() {
        val source = """
            func tick() {
                for {
                    select {
                    case <-time.After(3 * time.Second):
                        step()
                    }
                }
            }
        """.trimIndent()
        val findings = GoTimerAudit.scan(source)
        assertEquals(1, findings.size)
        assertEquals(GoTimerAudit.Kind.TimeAfterInLoop, findings.first().kind)
    }

    @Test
    fun `detects defer inside a for loop`() {
        val source = """
            func readAll(tags []string) {
                for _, tag := range tags {
                    f, _ := open(tag)
                    defer f.Close()
                }
            }
        """.trimIndent()
        val findings = GoTimerAudit.scan(source)
        assertEquals(1, findings.size)
        assertEquals(GoTimerAudit.Kind.DeferInLoop, findings.first().kind)
    }

    @Test
    fun `does not flag code outside loops`() {
        val source = """
            func once() {
                defer closeResource()
                select {
                case <-time.After(time.Second):
                }
            }
        """.trimIndent()
        assertEquals(0, GoTimerAudit.scan(source).size)
    }

    @Test
    fun `closing brace ends the loop scope`() {
        val source = """
            func mixed() {
                for i := 0; i < 3; i++ {
                    step(i)
                }
                defer cleanup()
            }
        """.trimIndent()
        assertEquals(0, GoTimerAudit.scan(source).size)
    }

    @Test
    fun `real proxy source is scanned without throwing and documents remaining debt`() {
        val source = TestProjectFiles.read("platform_live_go/flv_proxy.go")
        val findings = GoTimerAudit.scan(source)
        // 已知存量债：门禁先能看见它，后续逐条替换成 NewTicker / 作用域函数。
        assertTrue("flv_proxy.go 仍存在循环内 time.After/defer，见 T5-36 待办", findings.isNotEmpty())
    }
}
