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
    fun parsesColonTimezoneByAbsoluteOffsetAndRejectsTrailingJunk() {
        val xml = "<tv>" +
            "<programme start=\"20260101120000+08:00\" stop=\"20260101130000+08:00\" channel=\"c1\">" +
            "<title>带冒号时区</title></programme>" +
            "<programme start=\"20260101150000+08:00oops\" stop=\"20260101160000+08:00\" channel=\"c1\">" +
            "<title>尾部脏数据</title></programme>" +
            "</tv>"

        val parsed = parseXmltvEpg(xml)

        // 12:00+08:00 == 04:00 UTC，与运行测试设备的本地时区无关。
        assertEquals(1_767_240_000_000L, parsed["c1"]?.single()?.startMs)
        assertEquals(1_767_243_600_000L, parsed["c1"]?.single()?.stopMs)
        assertEquals(listOf("带冒号时区"), parsed["c1"]?.map { it.title })
    }

    @Test
    fun naiveTimestampsUseConfiguredTimezone() {
        val xml = "<tv>" +
            "<programme start=\"20260101120000\" stop=\"20260101130000\" channel=\"c1\">" +
            "<title>无时区后缀</title></programme>" +
            "</tv>"

        val shanghai = parseXmltvEpg(xml, "Asia/Shanghai")["c1"]?.single()
        val utc = parseXmltvEpg(xml, "UTC")["c1"]?.single()

        // 同一时间串按源时区解释：东八区 12:00 == UTC 04:00，相差 8 小时。
        assertEquals(1_767_240_000_000L, shanghai?.startMs)
        assertEquals(1_767_268_800_000L, utc?.startMs)
    }

    @Test
    fun explicitOffsetIgnoresConfiguredTimezone() {
        val xml = "<tv>" +
            "<programme start=\"20260101120000 +0800\" stop=\"20260101130000 +0800\" channel=\"c1\">" +
            "<title>带偏移</title></programme>" +
            "</tv>"

        val withTz = parseXmltvEpg(xml, "UTC")["c1"]?.single()
        val withoutTz = parseXmltvEpg(xml)["c1"]?.single()

        assertEquals(withTz?.startMs, withoutTz?.startMs)
        assertEquals(1_767_240_000_000L, withTz?.startMs)
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
