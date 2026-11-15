package net.prototux.aare.launch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneNavigationTest {
    @Test
    fun knownNavigationAppsCountWithoutBeingMarked() {
        assertTrue(isStandaloneNavigation("com.waze", emptySet()))
        assertTrue(isStandaloneNavigation("com.google.android.apps.maps", emptySet()))
    }

    @Test
    fun otherAppsCountOnlyWhenMarked() {
        assertFalse(isStandaloneNavigation("org.schabi.newpipe", emptySet()))
        assertFalse(isStandaloneNavigation("org.schabi.newpipe", setOf("net.osmand")))
        assertTrue(isStandaloneNavigation("net.osmand", setOf("net.osmand")))
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
