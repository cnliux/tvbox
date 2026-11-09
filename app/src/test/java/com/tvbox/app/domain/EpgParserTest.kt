package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgParserTest {

    @Test
    fun createsBothChannelIdAndDisplayNameKeys() {
        val xml = """
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20260101080000 +0800" stop="20260101090000 +0800" channel="cctv1"><title lang="zh">早间新闻</title></programme>
            </tv>
        """.trimIndent()

        val parsed = parseXmltvEpg(xml)

        assertEquals(listOf("早间新闻"), parsed["cctv1"]?.map { it.title })
        assertEquals(listOf("早间新闻"), parsed["CCTV-1 综合"]?.map { it.title })
        assertEquals(
            "早间新闻",
            findEpgPrograms(parsed, groupName = "央视", channelName = "CCTV-1综合").first().title,
        )
    }

    @Test
    fun mergesDuplicateProgrammesAndAliasesWithoutDuplication() {
        val programme =
            "<programme start=\"20260101080000 +0800\" stop=\"20260101090000 +0800\" channel=\"c1\">" +
                "<title>早间新闻</title></programme>"
        val xml = "<tv>" +
            "<channel id=\"c1\"><display-name>频道A</display-name><display-name>频道A</display-name></channel>" +
            programme + programme +
            "</tv>"

        val parsed = parseXmltvEpg(xml)

        assertEquals(1, parsed["c1"]?.size)
        assertEquals(1, parsed["频道A"]?.size)
    }

    @Test
    fun keepsProgrammesWithoutChannelDeclaration() {
        val xml = "<tv>" +
            "<programme start=\"20260101080000\" stop=\"20260101090000\" channel=\"orphan\">" +
            "<title>无频道声明</title></programme>" +
            "</tv>"

        val parsed = parseXmltvEpg(xml)

        assertEquals(listOf("无频道声明"), parsed["orphan"]?.map { it.title })
    }
}
