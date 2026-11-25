package com.tvbox.app.data.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetworkPolicy] / [NetworkPolicies] / [PolicyEventListener] 单测：
 * 不发起真实网络请求，仅校验 Builder 口径、连接池参数以及 EventListener 计数。
 */
class NetworkPolicyTest {

    private fun buildClient(policy: NetworkPolicy): OkHttpClient =
        OkHttpClient.Builder().applyPolicy(policy).build()

    private fun fakeCall(): Call =
        buildClient(NetworkPolicies.default).newCall(Request.Builder().url("http://127.0.0.1/").build())

    /** 反射读取 ConnectionPool 底层 RealConnectionPool 的 maxIdleConnections。 */
    private fun connectionPoolMaxIdle(pool: okhttp3.ConnectionPool): Int {
        val delegate = pool.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
            .get(pool)
        return delegate.javaClass.getDeclaredField("maxIdleConnections").apply { isAccessible = true }
            .getInt(delegate)
    }

    /** 反射读取 ConnectionPool 底层 RealConnectionPool 的 keepAliveDurationNs。 */
    private fun connectionPoolKeepAliveMs(pool: okhttp3.ConnectionPool): Long {
        val delegate = pool.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
            .get(pool)
        val ns = delegate.javaClass.getDeclaredField("keepAliveDurationNs").apply { isAccessible = true }
            .getLong(delegate)
        return TimeUnit.NANOSECONDS.toMillis(ns)
    }

    @Test
    fun `applyPolicy 应用超时口径`() {
        val policy = NetworkPolicy(
            connectTimeoutMs = 3_000,
            readTimeoutMs = 4_000,
            writeTimeoutMs = 5_000,
            callTimeoutMs = 9_000,
        )
        val client = buildClient(policy)
        // OkHttp 的 *TimeoutMillis 返回 Int，这里用 Int 字面量断言，避免 Int/Long 装箱判等失败。
        assertEquals(3_000, client.connectTimeoutMillis)
        assertEquals(4_000, client.readTimeoutMillis)
        assertEquals(5_000, client.writeTimeoutMillis)
        assertEquals(9_000, client.callTimeoutMillis)
    }

    @Test
    fun `applyPolicy 应用连接池空闲连接数`() {
        val policy = NetworkPolicy(maxIdleConnections = 7, keepAliveSeconds = 120)
        val client = buildClient(policy)
        // okhttp 5.x 的 ConnectionPool 未公开 maxIdle/keepAlive 访问器，通过反射读取底层字段校验。
        assertEquals(7, connectionPoolMaxIdle(client.connectionPool))
        assertEquals(120_000L, connectionPoolKeepAliveMs(client.connectionPool))
    }

    @Test
    fun `probe 的 callTimeout 严格小于 default`() {
        assertTrue(NetworkPolicies.probe.callTimeoutMs < NetworkPolicies.default.callTimeoutMs)
    }

    @Test
    fun `largeDownload 的 maxBodyBytes 大于 default`() {
        assertTrue(NetworkPolicies.largeDownload.maxBodyBytes > NetworkPolicies.default.maxBodyBytes)
    }

    @Test
    fun `snapshot 初始 totalCalls 为 0`() {
        val listener = PolicyEventListener(NetworkPolicies.default)
        val stats = listener.snapshot()
        assertEquals(0, stats.totalCalls)
        assertEquals(0, stats.failedCalls)
        assertEquals(0L, stats.avgDurationMs)
    }

    @Test
    fun `callEnd 后 totalCalls 等于 1`() {
        val listener = PolicyEventListener(NetworkPolicies.default)
        val call = fakeCall()
        listener.callStart(call)
        listener.callEnd(call)
        val stats = listener.snapshot()
        assertEquals(1, stats.totalCalls)
        assertEquals(0, stats.failedCalls)
    }

    @Test
    fun `callFailed 后 failedCalls 等于 1`() {
        val listener = PolicyEventListener(NetworkPolicies.default)
        val call = fakeCall()
        listener.callStart(call)
        listener.callFailed(call, IOException("模拟失败"))
        val stats = listener.snapshot()
        assertEquals(1, stats.totalCalls)
        assertEquals(1, stats.failedCalls)
    }

    @Test
    fun `avgDurationMs 按两次调用求平均`() {
        val listener = PolicyEventListener(NetworkPolicies.default)
        val call = fakeCall()
        listener.callStart(call); listener.callEnd(call)
        listener.callStart(call); listener.callEnd(call)
        val stats = listener.snapshot()
        assertEquals(2, stats.totalCalls)
        // 真实时钟下两次极短调用，平均耗时必然远小于任一超时口径；只需保证非负且合理。
        assertTrue(stats.avgDurationMs >= 0L)
        assertTrue(stats.avgDurationMs < NetworkPolicies.default.callTimeoutMs)
    }

    @Test
    fun `默认DNS三档全关以保持向后兼容`() {
        val p = NetworkPolicies.default
        assertEquals(DohMode.OFF, p.dohMode)
        assertEquals(false, p.dnsHappyEyeballs)
        assertTrue(p.dohHosts.isEmpty())
        assertEquals(250L, p.dnsRaceDelayMs)
        // 显式开启档使用 FALLBACK：仅在系统 DNS 失败后才访问第三方解析器。
        assertEquals(DohMode.FALLBACK, NetworkPolicies.parallelDns.dohMode)
    }

    @Test
    fun `默认口径与方案 P36 数值一致`() {
        val p = NetworkPolicies.default
        assertEquals(10_000L, p.connectTimeoutMs)
        assertEquals(15_000L, p.readTimeoutMs)
        assertEquals(15_000L, p.writeTimeoutMs)
        assertEquals(30_000L, p.callTimeoutMs)
        assertEquals(5, p.maxIdleConnections)
        assertEquals(300L, p.keepAliveSeconds)
        assertEquals(20L * 1024 * 1024, p.maxBodyBytes)
        assertEquals(60_000L, p.dnsCacheTtlMs)
    }
}
