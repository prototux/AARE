package net.prototux.aare.launch

import net.prototux.aare.launch.StandaloneDisplay.AUDIO_ONLY
import net.prototux.aare.launch.StandaloneDisplay.PAUSED
import net.prototux.aare.launch.StandaloneDisplay.SHOWN
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingRulesTest {
    private val rules = listOf(null) + StandaloneDrivingRule.entries

    @Test
    fun everythingIsShownWhileParked() {
        for (rule in rules) for (nav in listOf(false, true)) {
            assertEquals("$rule nav=$nav", SHOWN, standaloneDisplay(rule, driving = false, navigation = nav))
        }
    }

    @Test
    fun withoutARuleOnlyNavigationAppsStayWhileDriving() {
        assertEquals(PAUSED, standaloneDisplay(null, driving = true, navigation = false))
        assertEquals(SHOWN, standaloneDisplay(null, driving = true, navigation = true))
    }

    @Test
    fun anExplicitRuleWinsWhileDriving() {
        for (nav in listOf(false, true)) {
            assertEquals(PAUSED, standaloneDisplay(StandaloneDrivingRule.PARKED_ONLY, driving = true, navigation = nav))
            assertEquals(SHOWN, standaloneDisplay(StandaloneDrivingRule.ALLOWED, driving = true, navigation = nav))
            assertEquals(AUDIO_ONLY, standaloneDisplay(StandaloneDrivingRule.AUDIO_ONLY, driving = true, navigation = nav))
        }
    }

    @Test
    fun rulesSurviveStorage() {
        val stored = mapOf(
            "org.schabi.newpipe" to StandaloneDrivingRule.AUDIO_ONLY,
            "com.waze" to StandaloneDrivingRule.PARKED_ONLY,
            "net.osmand" to StandaloneDrivingRule.ALLOWED,
        )
        assertEquals(stored, parseDrivingRules(formatDrivingRules(stored)))
    }

    @Test
    fun unknownStoredEntriesAreSkipped() {
        assertEquals(
            mapOf("a.b" to StandaloneDrivingRule.ALLOWED),
            parseDrivingRules(setOf("a.b\tdriving", "c.d\tsometimes", "no-tab", "\tparked")),
        )
    }
}
