package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPairingTest {
    private val car = "AA:BB:CC:00:11:22"

    @Test
    fun startsOnlyWhenOptedInAndNotPaired() {
        assertTrue(AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = car, alreadyPaired = false, bonded = false))
        assertFalse("off by default is off", AutoPairing.shouldStart(enabled = false, shizukuAvailable = true, carAddress = car, alreadyPaired = false, bonded = false))
        assertFalse(AutoPairing.shouldStart(enabled = true, shizukuAvailable = false, carAddress = car, alreadyPaired = false, bonded = false))
        assertFalse("the car says it is paired", AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = car, alreadyPaired = true, bonded = false))
        assertFalse("the phone has the bond", AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = car, alreadyPaired = false, bonded = true))
        listOf(null, "", "not an address", "00:00:00:00:00:00", "AA:BB:CC:00:11").forEach {
            assertFalse("$it", AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = it, alreadyPaired = false, bonded = false))
        }
    }

    @Test
    fun theLogSaysWhyItDidntStart() {
        // Android 10 and 11: Shizuku reads as unavailable there, but the release is the reason.
        assertEquals("it needs Android 12 or later", AutoPairing.whyNot(29, shizukuAvailable = false))
        assertEquals("it needs Android 12 or later", AutoPairing.whyNot(30, shizukuAvailable = false))
        assertEquals("Shizuku isn't available", AutoPairing.whyNot(31, shizukuAvailable = false))
        assertEquals("no usable car address", AutoPairing.whyNot(34, shizukuAvailable = true))
    }
}
