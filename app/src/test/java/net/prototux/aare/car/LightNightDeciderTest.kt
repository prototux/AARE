package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Night mode from the phone's light sensor: on/off thresholds with hysteresis and a stability window. */
class LightNightDeciderTest {
    private fun decider() = LightNightDecider(nightBelowLux = 10f, dayAboveLux = 30f, stableMs = 3_000L)

    @Test
    fun startsInDayUntilItHasReasonNotTo() {
        val d = decider()
        assertFalse(d.night)
    }

    @Test
    fun aBriefDipBelowThresholdDoesNotFlipBeforeTheStabilityWindow() {
        val d = decider()
        assertFalse(d.onLux(2f, 0L))
        assertFalse(d.onLux(2f, 1_000L))
        assertFalse(d.night) // not yet 3 s
    }

    @Test
    fun flipsToNightOnceDarkHasHeldForTheStabilityWindow() {
        val d = decider()
        assertFalse(d.onLux(2f, 0L))
        assertFalse(d.onLux(2f, 2_000L))
        assertTrue(d.onLux(2f, 3_000L))
        assertTrue(d.night)
    }

    @Test
    fun aStreetlightGlintDuringTheWindowCancelsTheFlip() {
        val d = decider()
        assertFalse(d.onLux(2f, 0L)) // starts going dark...
        assertFalse(d.onLux(50f, 1_500L)) // ...but a bright flash resets the pending state
        // Dark again, but the window restarts from here, so 3 s after 0L isn't enough.
        assertFalse(d.onLux(2f, 2_000L))
        assertFalse(d.onLux(2f, 4_000L))
        assertTrue(d.onLux(2f, 5_000L))
    }

    @Test
    fun theDeadZoneBetweenThresholdsKeepsWhateverNightAlreadyIs() {
        val d = decider()
        assertFalse(d.onLux(2f, 0L))
        d.onLux(2f, 3_000L)
        assertTrue(d.night)
        // 20 lux is between the 10 (night) and 30 (day) thresholds: stays night.
        assertFalse(d.onLux(20f, 10_000L))
        assertTrue(d.night)
    }

    @Test
    fun goesBackToDayOnceBrightHasHeldForTheStabilityWindow() {
        val d = decider()
        d.onLux(2f, 0L)
        d.onLux(2f, 3_000L)
        assertTrue(d.night)
        assertFalse(d.onLux(50f, 4_000L))
        assertFalse(d.onLux(50f, 6_000L))
        assertTrue(d.onLux(50f, 7_000L))
        assertFalse(d.night)
    }

    @Test
    fun returnsFalseWhenAlreadyInThatState() {
        val d = decider()
        assertFalse(d.onLux(50f, 0L)) // already day, stays day
        assertEquals(false, d.night)
    }
}
