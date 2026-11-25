package com.tvbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5-31：电源资源总闸的释放保证与幂等性。 */
class PowerResourceRegistryTest {

    @Test
    fun releaseAllReleasesEveryRegisteredResourceExactlyOnce() {
        PowerResourceRegistry.clearForTest()
        val a = FakeResource()
        val b = FakeResource()
        PowerResourceRegistry.register(a)
        PowerResourceRegistry.register(b)
        assertEquals(2, PowerResourceRegistry.activeCount())
        assertEquals(2, PowerResourceRegistry.releaseAll())
        assertEquals(1, a.count)
        assertEquals(1, b.count)

        // 二次调用不应重复 release（句柄已出集合）。
        assertEquals(0, PowerResourceRegistry.releaseAll())
        assertEquals(1, a.count)
    }

    @Test
    fun unregisteredResourceIsNotReleasedByGate() {
        PowerResourceRegistry.clearForTest()
        val resource = FakeResource()
        val handle = PowerResourceRegistry.register(resource)
        PowerResourceRegistry.unregister(handle)
        PowerResourceRegistry.releaseAll()
        assertEquals(0, resource.count)
    }

    @Test
    fun oneFailingResourceDoesNotBlockOthers() {
        PowerResourceRegistry.clearForTest()
        val failing = PowerResourceRegistry.Releasable { error("release failed") }
        val good = FakeResource()
        PowerResourceRegistry.register(failing)
        PowerResourceRegistry.register(good)
        assertEquals(1, PowerResourceRegistry.releaseAll())
        assertTrue(good.count == 1)
    }

    private class FakeResource : PowerResourceRegistry.Releasable {
        var count = 0
        override fun releaseOnce() {
            count++
        }
    }
}
