package com.tvbox.app.data.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-05/06：用注入的 fake resolver / connector 验证分层降级、缓存命中、
 * IPv6/IPv4 竞速与默认关闭时的系统 DNS 直通，不发起任何真实网络请求。
 */
class ParallelDnsTest {

    private fun fakeDns(resolver: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolver(hostname)
    }

    private fun ipv4(): InetAddress = InetAddress.getByName("93.184.216.34")

    private fun ipv6(): InetAddress = InetAddress.getByName("2001:db8::1")

    @Test
    fun `默认关闭时直通系统DNS且不缓存`() {
        val calls = AtomicInteger()
        val system = fakeDns { host ->
            calls.incrementAndGet()
            listOf(InetAddress.getByName("1.1.1.1"))
        }
        val dns = ParallelDns(
            NetworkPolicies.default,
            systemDns = system,
            dohLookup = { _, _, _ -> throw AssertionError("DoH 关闭时不应被调用") },
        )

        val first = dns.lookup("example.com")
        val second = dns.lookup("example.com")

        assertEquals(2, calls.get())
        assertEquals(listOf(InetAddress.getByName("1.1.1.1")), first)
        assertEquals(first, second)
    }

    @Test
    fun `系统失败时按FALLBACK启用DoH`() {
        val dohCalls = AtomicInteger()
        val expected = ipv4()
        val dns = ParallelDns(
            NetworkPolicies.default.copy(dohMode = DohMode.FALLBACK),
            systemDns = fakeDns { throw UnknownHostException("模拟系统解析失败") },
            dohLookup = { _, _, type ->
                dohCalls.incrementAndGet()
                if (type == 1) listOf(expected) else emptyList()
            },
        )

        assertEquals(listOf(expected), dns.lookup("example.com"))
        assertTrue(dohCalls.get() >= 1)
    }

    @Test
    fun `PREFER优先DoH且DoH失败时回落系统DNS`() {
        val preferred = ipv4()
        val preferDns = ParallelDns(
            NetworkPolicies.default.copy(dohMode = DohMode.PREFER),
            systemDns = fakeDns { throw AssertionError("DoH 成功时不应查系统 DNS") },
            dohLookup = { _, _, type -> if (type == 1) listOf(preferred) else emptyList() },
        )
        assertEquals(listOf(preferred), preferDns.lookup("example.com"))

        val systemCalls = AtomicInteger()
        val fallback = ipv6()
        val fallbackDns = ParallelDns(
            NetworkPolicies.default.copy(dohMode = DohMode.PREFER),
            systemDns = fakeDns { systemCalls.incrementAndGet(); listOf(fallback) },
            dohLookup = { _, _, _ -> emptyList() },
        )
        assertEquals(listOf(fallback), fallbackDns.lookup("example.com"))
        assertEquals(1, systemCalls.get())
    }

    @Test
    fun `主DoH失败自动切备用模板`() {
        val attempted = mutableListOf<String>()
        val backup = ipv4()
        val dns = ParallelDns(
            NetworkPolicies.default.copy(dohMode = DohMode.FALLBACK),
            systemDns = fakeDns { throw UnknownHostException("模拟系统解析失败") },
            dohLookup = { template, _, type ->
                attempted.add(template)
                if (template.contains("cloudflare") && type == 1) listOf(backup) else emptyList()
            },
        )

        assertEquals(listOf(backup), dns.lookup("example.com"))
        assertTrue(attempted.any { it.contains("dns.google") })
        assertTrue(attempted.any { it.contains("cloudflare") })
    }

    @Test
    fun `静态hosts覆盖优先于系统DNS`() {
        val pinned = InetAddress.getByName("9.9.9.9")
        val dns = ParallelDns(
            NetworkPolicies.default.copy(dohHosts = mapOf("example.com" to listOf("9.9.9.9"))),
            systemDns = fakeDns { throw AssertionError("hosts 命中时不应查系统 DNS") },
            dohLookup = { _, _, _ -> throw AssertionError("hosts 命中时不应查 DoH") },
        )

        assertEquals(listOf(pinned), dns.lookup("example.com"))
    }

    @Test
    fun `缓存命中后TTL过期重新解析`() {
        var clock = 1_000L
        val calls = AtomicInteger()
        val dns = ParallelDns(
            NetworkPolicies.default.copy(dohMode = DohMode.FALLBACK, dnsCacheTtlMs = 60_000L),
            systemDns = fakeDns { calls.incrementAndGet(); listOf(ipv4()) },
            dohLookup = { _, _, _ -> throw AssertionError("系统解析成功时不应查 DoH") },
            nowMs = { clock },
        )

        dns.lookup("example.com")
        dns.lookup("example.com")
        assertEquals(1, calls.get())
        clock += 60_001L
        dns.lookup("example.com")
        assertEquals(2, calls.get())
    }

    @Test
    fun `HappyEyeballs中IPv4先连通则前置并取消IPv6探测`() {
        val v6 = ipv6()
        val v4 = ipv4()
        val interrupted = CountDownLatch(1)
        val connector = AddressConnector { address, _, _ ->
            if (address == v6) {
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
                false
            } else {
                true
            }
        }
        val dns = ParallelDns(
            NetworkPolicies.default.copy(dnsHappyEyeballs = true, dnsRaceDelayMs = 80L),
            systemDns = fakeDns { listOf(v6, v4) },
            connector = connector,
        )

        val result = dns.lookup("example.com")

        assertEquals(v4, result.first())
        assertEquals(2, result.size)
        assertTrue(interrupted.await(2L, TimeUnit.SECONDS))
    }

    @Test
    fun `HappyEyeballs两路都不通时保持系统返回顺序`() {
        val v6 = ipv6()
        val v4 = ipv4()
        val dns = ParallelDns(
            NetworkPolicies.default.copy(dnsHappyEyeballs = true, dnsRaceDelayMs = 40L),
            systemDns = fakeDns { listOf(v6, v4) },
            connector = AddressConnector { _, _, _ -> false },
        )

        assertEquals(listOf(v6, v4), dns.lookup("example.com"))
    }
}
