package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P28：EPG 解析安全加固测试。
 *
 * 覆盖：
 * 1. XXE fixture：DOCTYPE + ENTITY 声明必须整体拒绝，文件路径不得出现在解析结果中。
 * 2. Billion laughs fixture：十层嵌套实体必须在秒级完成且不展开。
 * 3. 超大单条 programme：超过 1MB 的条目被跳过，其余正常节目保留。
 * 4. 嵌套深度：channel/programme 自嵌套不崩溃。
 */
class EpgHardeningTest {

    /** 合法 XMLTV 基线：用于对照"拒绝攻击文本后仍能解析正常部分"。 */
    private val validXmltv = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="cctv1"><display-name>CCTV-1</display-name></channel>
          <programme channel="cctv1" start="20260101080000 +0800" stop="20260101090000 +0800">
            <title>新闻联播</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun `XXE fixture 拒绝 DOCTYPE 与 ENTITY 声明`() {
        val xxe = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
              <!ENTITY secret "top-secret-value">
            ]>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 &xxe;</display-name></channel>
              <programme channel="cctv1" start="20260101080000 +0800" stop="20260101090000 +0800">
                <title>&secret;</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = parseXmltvEpg(xxe)

        // P28：命中 DOCTYPE 即返回空 Map，绝不展开实体。
        assertTrue("含 DOCTYPE 的 EPG 必须返回空表", result.isEmpty())
        val serialized = result.toString()
        assertFalse("解析结果不得包含 /etc/passwd", serialized.contains("/etc/passwd"))
        assertFalse("解析结果不得包含实体展开内容", serialized.contains("top-secret-value"))
    }

    @Test
    fun `Billion laughs 十层嵌套实体秒级完成且不展开`() {
        // 构造十层实体嵌套：a="lol"，b="&a;" x9，c="&b;" x9 ... j="&i;" x9。
        // 若展开将是 9^9 ≈ 3.8 亿个 "lol"，任何展开式解析器都会爆炸；本项目必须整体拒绝。
        val entities = buildString {
            appendLine("<!DOCTYPE tv [")
            appendLine("""  <!ENTITY a "lol">""")
            for (level in 2..10) {
                val prev = ('a' + level - 2)
                val cur = ('a' + level - 1)
                appendLine("""  <!ENTITY $cur "${"&$prev;".repeat(9)}">""")
            }
            appendLine("]>")
            appendLine("<tv>")
            appendLine("""  <channel id="cctv1"><display-name>CCTV-1 &j;</display-name></channel>""")
            appendLine("</tv>")
        }

        val startNs = System.nanoTime()
        val result = parseXmltvEpg(entities)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        // P28：命中 ENTITY 即返回空 Map，不得出现指数展开。
        assertTrue("含 ENTITY 的 EPG 必须返回空表", result.isEmpty())
        assertTrue(
            "Billion laughs 必须秒级完成，实际耗时 ${elapsedMs}ms",
            elapsedMs < 1_000,
        )
        assertFalse("结果不得包含 lol 展开内容", result.toString().contains("lol"))
    }

    @Test
    fun `超过 1MB 的单条 programme 被跳过，正常节目保留`() {
        val hugeTitle = "A".repeat(1_500_000) // 1.5MB 单条 title
        val mixed = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<tv>")
            appendLine("""  <channel id="cctv1"><display-name>CCTV-1</display-name></channel>""")
            // 正常节目：应保留
            appendLine(
                """  <programme channel="cctv1" start="20260101080000 +0800" stop="20260101090000 +0800">""",
            )
            appendLine("    <title>正常节目</title>")
            appendLine("  </programme>")
            // 超大节目：应跳过
            appendLine(
                """  <programme channel="cctv1" start="20260102080000 +0800" stop="20260102090000 +0800">""",
            )
            appendLine("    <title>$hugeTitle</title>")
            appendLine("  </programme>")
            appendLine("</tv>")
        }

        val result = parseXmltvEpg(mixed)
        val programmes = result["cctv1"].orEmpty()

        assertEquals("只保留 1 条正常节目", 1, programmes.size)
        assertEquals("正常节目标题", "正常节目", programmes.first().title)
        assertFalse("超大节目不得被解析", programmes.any { it.title.length > 1_000_000 })
    }

    @Test
    fun `channel 与 programme 三层自嵌套不崩溃`() {
        val nested = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="a">
                <channel id="b">
                  <channel id="c"><display-name>deep</display-name></channel>
                </channel>
              </channel>
              <programme channel="a" start="20260101080000 +0800" stop="20260101090000 +0800">
                <programme channel="b" start="20260101080000 +0800" stop="20260101090000 +0800">
                  <title>nested-programme</title>
                </programme>
              </programme>
            </tv>
        """.trimIndent()

        // 不崩溃即可；深度截断后保留的部分不做严格断言。
        val result = parseXmltvEpg(nested)
        assertTrue("返回结果应为 Map 类型", result is Map<*, *>)
    }

    @Test
    fun `合法 XMLTV 不受防护影响仍正常解析`() {
        val result = parseXmltvEpg(validXmltv)
        val programmes = result["cctv1"].orEmpty()
        assertEquals(1, programmes.size)
        assertEquals("新闻联播", programmes.first().title)
    }

    @Test
    fun `外部 DOCTYPE 无内部子集不误杀真实EPG源`() {
        // 真实 EPG 源（51zmt / 112114 / erw）普遍带 `<!DOCTYPE tv SYSTEM "xmltv.dtd">`：
        // 外部 DTD、无内部子集、无 ENTITY 声明。正则解析器既不联网取 DTD 也不展开实体，
        // 放行它才能解析这类源；内置默认节目单正是此格式。
        val externalDoctype = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv generator-info-name="老张的EPG">
              <channel id="1"><display-name lang="zh">CCTV1</display-name></channel>
              <programme start="20261127000730 +0800" stop="20261127003900 +0800" channel="1">
                <title>今日说法</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = parseXmltvEpg(externalDoctype)
        assertEquals("外部 DOCTYPE 的合法源应正常解析", 1, result["CCTV1"].orEmpty().size)
        assertEquals("今日说法", result["CCTV1"].orEmpty().first().title)
    }

    @Test
    fun `L5 注释与CDATA内的DOCTYPE字面不误杀合法EPG`() {
        // 注释里提到 <!DOCTYPE、CDATA 中出现 <!DOCTYPE 字面：都是正文内容，不是真实 DTD 声明，
        // 不应触发整份拒绝。真实 DTD（正文声明）仍由 XXE fixture 用例保证拒绝。
        val benignMention = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <!-- this feed contains no ENTITY and no DOCTYPE declaration -->
              <channel id="cctv1"><display-name>CCTV-1</display-name></channel>
              <programme channel="cctv1" start="20260101080000 +0800" stop="20260101090000 +0800">
                <title>新闻联播</title>
                <desc><![CDATA[文档里提到 <!DOCTYPE html> 只是举例]]></desc>
              </programme>
            </tv>
        """.trimIndent()

        val result = parseXmltvEpg(benignMention)
        assertEquals("注释/CDATA 内 DOCTYPE 字面不应整份拒绝", 1, result["cctv1"].orEmpty().size)
    }
}
