package net.prototux.aare.host.templates

import net.prototux.aare.host.TemplateQuirks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCountdownTest {
    @Test
    fun theDefaultActionCountsDownTenSeconds() {
        assertEquals(RowCountdown(1, 10_000), rowCountdown(listOf(false, true), timedParams = false) { null })
    }

    @Test
    fun theDefaultActionWinsOverAnActionsOwnCountdown() {
        val own = mapOf(0 to 3_000L)
        assertEquals(RowCountdown(1, 10_000), rowCountdown(listOf(false, true), timedParams = true) { own[it] })
    }

    @Test
    fun ownCountdownsOnlyForAllowedApps() {
        val own = mapOf(1 to 5_000L, 2 to 7_000L)
        assertNull(rowCountdown(listOf(false, false, false), timedParams = false) { own[it] })
        // only the first one of the row
        assertEquals(RowCountdown(1, 5_000), rowCountdown(listOf(false, false, false), timedParams = true) { own[it] })
    }

    @Test
    fun noCountdownInARowWithoutOne() {
        assertNull(rowCountdown(listOf(false, false), timedParams = true) { null })
        assertNull(rowCountdown(emptyList(), timedParams = true) { null })
    }

    @Test
    fun readsTheDurationInSeconds() {
        assertEquals(5_000L, timedActionParamsMillis("TimedActionParams|duration:5"))
        assertEquals(30_000L, timedActionParamsMillis("TimedActionParams|style:bar, duration : 30 "))
    }

    @Test
    fun aMissingOrBadDurationIsTenSeconds() {
        assertEquals(10_000L, timedActionParamsMillis("TimedActionParams"))
        assertEquals(10_000L, timedActionParamsMillis("TimedActionParams|duration:soon"))
        assertEquals(10_000L, timedActionParamsMillis("TimedActionParams|duration:0"))
        assertEquals(10_000L, timedActionParamsMillis("TimedActionParams|duration:-4"))
    }

    @Test
    fun otherVariantsAreNoCountdownAndStayVisible() {
        assertNull(timedActionParamsMillis("Start"))
        assertNull(timedActionParamsMillis("Go|duration:5"))
        assertFalse(isTimedActionParams("Go"))
        assertTrue(isTimedActionParams("TimedActionParams|duration:5"))
    }

    @Test
    fun ownCountdownApps() {
        assertTrue(TemplateQuirks.timedActionParams("com.waze"))
        assertTrue(TemplateQuirks.timedActionParams("com.ubercab.driver"))
        assertTrue(TemplateQuirks.timedActionParams("androidx.car.app.sample.navigation"))
        assertFalse(TemplateQuirks.timedActionParams("com.example.app"))
    }
}
