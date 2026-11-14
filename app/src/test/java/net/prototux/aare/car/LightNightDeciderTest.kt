package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Night mode from the phone's light sensor: on/off thresholds with hysteresis and a stability window. */
class LightNightDeciderTest {
    private fun decider() = LightNightDecider(nightBelowLux = 10f, dayAboveLux = 30f, stableMs = 3_000L)

    /** A decider that has settled on night (dark held for the window). */
    private fun atNight() = decider().also { it.onLux(2f, 0L); it.onLux(2f, 3_000L) }

    @Test
    fun startsUnknownUntilAReadingHolds() {
        val d = decider()
        assertNull(d.night)
        assertFalse(d.onLux(50f, 0L))
        assertNull(d.night) // not yet 3 s
    }

    @Test
    fun theFirstStableDaylightIsReported() {
        // A bright start must be reported too, or the screen would keep the car's (or the dark) default.
        val d = decider()
        assertFalse(d.onLux(50f, 0L))
        assertTrue(d.onLux(50f, 3_000L))
        assertEquals(false, d.night)
    }

    @Test
    fun aSteadyReadingIsCommittedByTheTimerWithoutAnotherEvent() {
        // TYPE_LIGHT only sends on change: a steady room sends one event and then nothing.
        val d = decider()
        assertFalse(d.onLux(2f, 0L))
        assertEquals(3_000L, d.msUntilStable(0L))
        assertEquals(1_000L, d.msUntilStable(2_000L))
        assertFalse(d.onTick(2_999L))
        assertTrue(d.onTick(3_000L))
        assertEquals(true, d.night)
        assertNull(d.msUntilStable(3_000L)) // nothing pending any more
        assertFalse(d.onTick(10_000L))
    }

    @Test
    fun aBriefDipBelowThresholdDoesNotFlipBeforeTheStabilityWindow() {
        val d = decider()
        assertFalse(d.onLux(50f, 0L))
        assertTrue(d.onTick(3_000L))
        assertFalse(d.onLux(2f, 4_000L))
        assertFalse(d.onLux(2f, 5_000L))
        assertEquals(false, d.night) // not yet 3 s of dark
    }

    @Test
    fun aStreetlightGlintDuringTheWindowCancelsTheFlip() {
        val d = decider()
        assertFalse(d.onLux(2f, 0L)) // starts going dark...
        assertFalse(d.onLux(50f, 1_500L)) // ...but a bright flash restarts the window
        // Dark again, but the window restarts from here, so 3 s after 0L isn't enough.
        assertFalse(d.onLux(2f, 2_000L))
        assertFalse(d.onLux(2f, 4_000L))
        assertTrue(d.onLux(2f, 5_000L))
        assertEquals(true, d.night)
    }

    @Test
    fun theDeadZoneBetweenThresholdsKeepsWhateverNightAlreadyIs() {
        val d = atNight()
        assertEquals(true, d.night)
        // 20 lux is between the 10 (night) and 30 (day) thresholds: stays night.
        assertFalse(d.onLux(20f, 10_000L))
        assertFalse(d.onTick(20_000L))
        assertEquals(true, d.night)
    }

    @Test
    fun aFirstReadingInTheDeadZoneTakesTheNearerThreshold() {
        val dim = decider()
        dim.onLux(15f, 0L)
        assertTrue(dim.onTick(3_000L))
        assertEquals(true, dim.night)
        val bright = decider()
        bright.onLux(25f, 0L)
        assertTrue(bright.onTick(3_000L))
        assertEquals(false, bright.night)
    }

    @Test
    fun goesBackToDayOnceBrightHasHeldForTheStabilityWindow() {
        val d = atNight()
        assertFalse(d.onLux(50f, 4_000L))
        assertFalse(d.onLux(50f, 6_000L))
        assertTrue(d.onLux(50f, 7_000L))
        assertEquals(false, d.night)
    }

    @Test
    fun returnsFalseWhenAlreadyInThatState() {
        val d = atNight()
        assertFalse(d.onLux(2f, 8_000L)) // already night, stays night
        assertNull(d.msUntilStable(8_000L))
    }
}
