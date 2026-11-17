package net.prototux.aare.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnsOnTest {
    @Test
    fun chosenTurnsOn() = assertTrue(turnsOn(true, 0L, 1_000_000L))

    @Test
    fun aReconnectAfterAShortDropTurnsItBackOn() = assertTrue(turnsOn(false, 1_000_000L, 1_030_000L))

    @Test
    fun aLaterDriveLeavesItOff() {
        assertFalse(turnsOn(false, 1_000_000L, 1_000_000L + RECONNECT_MS))
        assertFalse(turnsOn(false, 0L, 1_000_000L))
    }
}
