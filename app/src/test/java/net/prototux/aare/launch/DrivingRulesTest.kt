package net.prototux.aare.launch

import android.content.pm.ApplicationInfo
import net.prototux.aare.launch.StandaloneDisplay.AUDIO_ONLY
import net.prototux.aare.launch.StandaloneDisplay.PAUSED
import net.prototux.aare.launch.StandaloneDisplay.SHOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingRulesTest {
    private val rules = listOf(null) + StandaloneDrivingRule.entries

    @Test
    fun everythingIsShownWhileParkedOrInPassengerMode() {
        for (rule in rules) for (nav in listOf(false, true)) {
            assertEquals("$rule nav=$nav", SHOWN, standaloneDisplay(rule, drivingRestricted = false, navigation = nav))
        }
    }

    @Test
    fun withoutARuleEveryAppIsParkedOnly() {
        assertEquals(PAUSED, standaloneDisplay(null, drivingRestricted = true, navigation = false))
        assertEquals(PAUSED, standaloneDisplay(null, drivingRestricted = true, navigation = true))
    }

    @Test
    fun anOfferedRuleWinsWhileDriving() {
        for (nav in listOf(false, true)) {
            assertEquals(PAUSED, standaloneDisplay(StandaloneDrivingRule.PARKED_ONLY, drivingRestricted = true, navigation = nav))
            assertEquals(AUDIO_ONLY, standaloneDisplay(StandaloneDrivingRule.AUDIO_ONLY, drivingRestricted = true, navigation = nav))
        }
        assertEquals(SHOWN, standaloneDisplay(StandaloneDrivingRule.NAVIGATION, drivingRestricted = true, navigation = true))
    }

    @Test
    fun onlyNavigationAppsAreOfferedNavigation() {
        assertTrue(StandaloneDrivingRule.NAVIGATION in StandaloneDrivingRule.offered(navigation = true))
        assertFalse(StandaloneDrivingRule.NAVIGATION in StandaloneDrivingRule.offered(navigation = false))
        // Stored for an app that isn't (or no longer is) a navigation app: parked only.
        assertEquals(StandaloneDrivingRule.PARKED_ONLY, effectiveDrivingRule(StandaloneDrivingRule.NAVIGATION, navigation = false))
        assertEquals(PAUSED, standaloneDisplay(StandaloneDrivingRule.NAVIGATION, drivingRestricted = true, navigation = false))
    }

    @Test
    fun navigationAppsAreKnownOnesOrMapsApps() {
        assertTrue(isNavigationApp("com.waze", ApplicationInfo.CATEGORY_UNDEFINED))
        assertTrue(isNavigationApp("net.osmand", ApplicationInfo.CATEGORY_MAPS))
        assertFalse(isNavigationApp("org.schabi.newpipe", ApplicationInfo.CATEGORY_VIDEO))
    }

    @Test
    fun rulesSurviveStorage() {
        val stored = mapOf(
            "org.schabi.newpipe" to StandaloneDrivingRule.AUDIO_ONLY,
            "com.waze" to StandaloneDrivingRule.PARKED_ONLY,
            "net.osmand" to StandaloneDrivingRule.NAVIGATION,
        )
        assertEquals(stored, parseDrivingRules(formatDrivingRules(stored)))
    }

    @Test
    fun unknownStoredEntriesAreSkipped() {
        assertEquals(
            mapOf("a.b" to StandaloneDrivingRule.NAVIGATION),
            parseDrivingRules(setOf("a.b\tnavigation", "c.d\tsometimes", "e.f\tdriving", "no-tab", "\tparked")),
        )
    }
}
