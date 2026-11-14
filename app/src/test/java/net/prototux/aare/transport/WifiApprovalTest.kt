package net.prototux.aare.transport

import net.prototux.aare.transport.wireless.WifiApproval
import net.prototux.aare.transport.wireless.WifiApproval.Way
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiApprovalTest {
    private val car = "AA:BB:CC:00:11:22"
    private val accessPoint = "AA:BB:CC:00:11:23"

    @Test
    fun notAssociatedIsTodaysFlow() {
        assertEquals(Way.DIALOG, WifiApproval.way(emptySet(), car, accessPoint, shizukuOptIn = true, shizukuAvailable = true))
        assertEquals("another car's association doesn't count", Way.DIALOG,
            WifiApproval.way(setOf("11:22:33:44:55:66"), car, accessPoint, shizukuOptIn = true, shizukuAvailable = true))
    }

    @Test
    fun anAssociationWithTheAccessPointNeedsNothingMore() {
        assertEquals(Way.COMPANION, WifiApproval.way(setOf(accessPoint.lowercase()), car, accessPoint, shizukuOptIn = false, shizukuAvailable = false))
        // a head unit that uses its Bluetooth address for its access point too
        assertEquals(Way.COMPANION, WifiApproval.way(setOf(car), car, car, shizukuOptIn = true, shizukuAvailable = true))
    }

    @Test
    fun shizukuOnlyForAnAssociatedCarWhenOptedInAndUsable() {
        assertEquals(Way.SHIZUKU, WifiApproval.way(setOf(car), car, accessPoint, shizukuOptIn = true, shizukuAvailable = true))
        assertEquals(Way.SHIZUKU, WifiApproval.way(setOf(car), car.lowercase(), null, shizukuOptIn = true, shizukuAvailable = true))
        assertEquals(Way.DIALOG, WifiApproval.way(setOf(car), car, accessPoint, shizukuOptIn = false, shizukuAvailable = true))
        assertEquals(Way.DIALOG, WifiApproval.way(setOf(car), car, accessPoint, shizukuOptIn = true, shizukuAvailable = false))
    }
}
