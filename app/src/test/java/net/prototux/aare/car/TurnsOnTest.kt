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

    @Test
    fun alwaysTurnsOffTheDriversLocationToo() {
        assertTrue(locationSwitches(on = false, enabled = true, ours = false, always = true))
        assertFalse(locationSwitches(on = false, enabled = false, ours = false, always = true))
    }

    /**
     * A session start decides anew: AARE's only once it turns the location on itself. Found on, or
     * off and left alone, the driver's from then on, so a later off doesn't take it.
     */
    @Test
    fun aSessionStartForgetsAnEarlierOn() {
        assertFalse(stillOurs(on = true, ours = true))
        assertTrue(stillOurs(on = false, ours = true)) // an off keeps what AARE turned on
    }
}
