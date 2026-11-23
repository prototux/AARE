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

    @Test
    fun offOnlyWhatAareTurnedOn() {
        assertTrue(locationSwitches(on = true, enabled = false, ours = false))
        assertFalse(locationSwitches(on = true, enabled = true, ours = false))
        assertTrue(locationSwitches(on = false, enabled = true, ours = true))
        // The driver had it on: it stays on when the car goes.
        assertFalse(locationSwitches(on = false, enabled = true, ours = false))
        assertFalse(locationSwitches(on = false, enabled = false, ours = true))
    }
}
