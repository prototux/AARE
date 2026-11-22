package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppQuirksTest {
    @Test
    fun parsesAndroidAutoAppSpecs() {
        assertEquals(AppVersions("com.waze", 1023092, Long.MAX_VALUE), AppVersions.parse("com.waze:1023092:"))
        assertEquals(AppVersions("com.waze", 0, 1030544), AppVersions.parse("com.waze::1030544"))
        assertEquals(AppVersions("com.example", 0, Long.MAX_VALUE), AppVersions.parse("com.example::"))
        assertEquals(AppVersions("com.example", 5, 9), AppVersions.parse("com.example:5:9"))
    }

    @Test
    fun rejectsMalformedSpecs() {
        assertNull(AppVersions.parse("com.waze"))
        assertNull(AppVersions.parse("com.waze:1"))
        assertNull(AppVersions.parse("com.waze:x:"))
        assertNull(AppVersions.parse("::"))
        assertNull(AppVersions.parse("com.waze:9:5"))
    }

    @Test
    fun versionBoundsAreInclusive() {
        val list = AppQuirks.CLUSTER_DISABLED
        assertTrue(list.matches("com.waze", 1))
        assertTrue(list.matches("com.waze", 1030544))
        assertFalse(list.matches("com.waze", 1030545))
        assertTrue(list.matches("com.example", Long.MAX_VALUE))
    }

    @Test
    fun onlyTheListedPackageMatches() {
        assertFalse(AppQuirks.CLUSTER_DISABLED.matches("com.example.navigation", 1))
        assertFalse(AppQuirks.CLUSTER_DISABLED.matches("com.waze.beta", 1))
        assertTrue("com.waze" in AppQuirks.CLUSTER_DISABLED)
    }

    @Test
    fun anUnknownVersionNeverMatches() {
        // It can't be told where the app stands then, so the workaround stays off.
        assertFalse(AppQuirks.CLUSTER_DISABLED.matches("com.example", null))
        assertFalse(AppQuirks.NAVIGATION_ALERTS_AS_NOTIFICATIONS.matches("com.ubercab.driver", null))
    }

    @Test
    fun navigationAlertsAsNotifications() {
        val list = AppQuirks.NAVIGATION_ALERTS_AS_NOTIFICATIONS
        assertFalse(list.matches("com.waze", 1023091))
        assertTrue(list.matches("com.waze", 1023092))
        assertTrue(list.matches("com.ubercab.driver", 1))
        assertTrue(list.matches("androidx.car.app.sample.navigation", 1))
        assertFalse(list.matches("com.google.android.apps.maps", 1_100_000_000))
    }

    @Test
    fun onlyWazeTakesWazeFormat() {
        assertFalse(AppQuirks.takesWazeNavigationFormat("com.waze", 1023097))
        assertTrue(AppQuirks.takesWazeNavigationFormat("com.waze", 1023098))
        // Listed, but not Waze: the samples get the Car App Library format.
        assertFalse(AppQuirks.takesWazeNavigationFormat("androidx.car.app.sample.navigation", 1))
    }

    @Test
    fun dashboardNavigationMinimumVersions() {
        assertFalse(AppQuirks.dashboardNavigationSupported("com.locnall.KimGiSa", 40401))
        assertTrue(AppQuirks.dashboardNavigationSupported("com.locnall.KimGiSa", 40402))
        assertFalse(AppQuirks.dashboardNavigationSupported("com.google.android.apps.maps", 1066723823))
        assertTrue(AppQuirks.dashboardNavigationSupported("com.google.android.apps.maps", 1066723824))
    }

    @Test
    fun anUnreadableVersionKeepsTheMapCard() {
        assertTrue(AppQuirks.dashboardNavigationSupported("com.locnall.KimGiSa", null))
        assertTrue(AppQuirks.dashboardNavigationSupported("com.google.android.apps.maps", null))
    }

    @Test
    fun anAppWithoutAMinimumKeepsTheMapCard() {
        assertTrue(AppQuirks.dashboardNavigationSupported("com.google.android.apps.gmm.car", 1))
    }

    @Test
    fun wazeAndOtherAppsAlwaysRunOnTheDashboard() {
        assertTrue(AppQuirks.dashboardNavigationSupported("com.waze", 1))
        assertTrue(AppQuirks.dashboardNavigationSupported("com.waze", null))
        assertTrue(AppQuirks.dashboardNavigationSupported("net.osmand", null))
    }
}
