package com.tvbox.app.data.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * T1-09 / T1-08：验证 HttpClients 各出口的 connect/read/write/call 全部来自
 * [Timeouts.forScenario]，且 [HttpClients.forPlayback] 暴露首帧档位的播放 client。
 */
class HttpClientsTimeoutTest {

    private fun assertBudget(client: OkHttpClient, scenario: Timeouts.Scenario) {
        val budget = Timeouts.forScenario(scenario)
        assertEquals(budget.connectMs.toInt(), client.connectTimeoutMillis)
        assertEquals(budget.readMs.toInt(), client.readTimeoutMillis)
        // Timeouts 没有独立写档，按同一档的读预算处理。
        assertEquals(budget.readMs.toInt(), client.writeTimeoutMillis)
        assertEquals(budget.totalMs.toInt(), client.callTimeoutMillis)
    }

    @Test
    fun `default使用DETAIL档位而非硬编码秒数`() {
        assertBudget(HttpClients.default, Timeouts.Scenario.DETAIL)
    }

    @Test
    fun `probe使用PROBE档位`() {
        assertBudget(HttpClients.probe, Timeouts.Scenario.PROBE)
    }

    @Test
    fun `poster使用POSTER档位`() {
        assertBudget(HttpClients.poster, Timeouts.Scenario.POSTER)
    }

    @Test
    fun `playback使用FIRST_FRAME档位且forPlayback返回同一实例`() {
        assertBudget(HttpClients.playback, Timeouts.Scenario.FIRST_FRAME)
        assertSame(HttpClients.playback, HttpClients.forPlayback())
    }

    @Test
    fun `download使用OTA档位`() {
        assertBudget(HttpClients.download, Timeouts.Scenario.OTA)
    }
}
