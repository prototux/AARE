package net.prototux.aare.launch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneNavigationTest {
    @Test
    fun onlyANavigationAppWithTheNavigationRuleCounts() {
        assertTrue(isStandaloneNavigation(StandaloneDrivingRule.NAVIGATION, navigationApp = true))
        // Navigation apps are parked only until the user chooses otherwise.
        assertFalse(isStandaloneNavigation(null, navigationApp = true))
        assertFalse(isStandaloneNavigation(StandaloneDrivingRule.AUDIO_ONLY, navigationApp = true))
        assertFalse(isStandaloneNavigation(StandaloneDrivingRule.NAVIGATION, navigationApp = false))
    }

    @Test
    fun theLauncherOpensOnlyPlaces() {
        assertTrue(launcherMayView("geo:0,0?q=Amsterdam"))
        assertTrue(launcherMayView("GEO:48.85,2.29"))
        assertFalse(launcherMayView("https://example.com"))
        assertFalse(launcherMayView("intent:#Intent;component=com.android.settings/.Settings;end"))
        assertFalse(launcherMayView("content://contacts/people/1"))
        assertFalse(launcherMayView(""))
    }
}
