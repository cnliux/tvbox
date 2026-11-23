package com.tvbox.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EPG 节目时间状态：回看/预约入口只依据时间判断，实际能力由片源决定。 */
class EpgProgramStatusTest {

    private val now = 10_000_000L

    @Test
    fun endedProgramIsReplayable() {
        val program = EpgProgram("新闻联播", now - 7_200_000L, now - 6_480_000L)
        assertTrue(program.isReplayable(now))
        assertFalse(program.isUpcoming(now))
    }

    @Test
    fun ongoingProgramIsNeitherReplayableNorUpcoming() {
        val program = EpgProgram("正在直播", now - 3_600_000L, now + 3_600_000L)
        assertFalse(program.isReplayable(now))
        assertFalse(program.isUpcoming(now))
    }

    @Test
    fun futureProgramIsUpcomingOnly() {
        val program = EpgProgram("晚间剧场", now + 3_600_000L, now + 7_200_000L)
        assertFalse(program.isReplayable(now))
        assertTrue(program.isUpcoming(now))
    }

    @Test
    fun programWithoutValidStopTimeIsNotReplayable() {
        assertFalse(EpgProgram("无结束时间", 0L, 0L).isReplayable(now))
    }
}
