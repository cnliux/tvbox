package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLineSwitchMachineTest {

    private fun request(
        trigger: LineSwitchTrigger,
        lineCount: Int = 3,
        inFlight: Boolean = false,
        autoSwitchCount: Int = 0,
    ) = LineSwitchRequest(trigger, lineCount, 0, 7L, inFlight, autoSwitchCount)

    @Test
    fun userSwitchOverridesInFlightRequest() {
        assertEquals(
            LineSwitchDecision.Switch(1, 8L, 0L),
            decideLineSwitch(request(LineSwitchTrigger.User, inFlight = true)),
        )
    }

    @Test
    fun userSwitchOnSingleLineIsIgnored() {
        assertTrue(decideLineSwitch(request(LineSwitchTrigger.User, lineCount = 1)) is LineSwitchDecision.Ignored)
    }

    @Test
    fun autoSwitchSkipsWhenAlreadyInFlight() {
        assertTrue(
            decideLineSwitch(request(LineSwitchTrigger.AutoFailure, inFlight = true))
                is LineSwitchDecision.Ignored,
        )
    }

    @Test
    fun autoSwitchBackoffGrowsExponentially() {
        val delays = (0..2).map { count ->
            (decideLineSwitch(request(LineSwitchTrigger.AutoFailure, autoSwitchCount = count))
                as LineSwitchDecision.Switch).delayMs
        }
        assertEquals(listOf(300L, 600L, 1_200L), delays)
    }

    @Test
    fun autoSwitchExhaustsAfterLimit() {
        assertTrue(
            decideLineSwitch(request(LineSwitchTrigger.StartTimeout, autoSwitchCount = 3))
                is LineSwitchDecision.Exhausted,
        )
    }

    @Test
    fun singleLineAutoSwitchKeepsIndexAndBacksOff() {
        val decision = decideLineSwitch(request(LineSwitchTrigger.AutoFailure, lineCount = 1))
            as LineSwitchDecision.Switch
        assertEquals(0, decision.targetLine)
        assertEquals(300L, decision.delayMs)
    }

    @Test
    fun noAvailableLineIsIgnored() {
        assertTrue(decideLineSwitch(request(LineSwitchTrigger.User, lineCount = 0)) is LineSwitchDecision.Ignored)
    }
}
