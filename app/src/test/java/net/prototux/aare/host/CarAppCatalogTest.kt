package net.prototux.aare.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAppCatalogTest {
    private val navTemplates = CarAppInfo.PERMISSION_NAVIGATION_TEMPLATES
    private val accessSurface = CarAppInfo.PERMISSION_ACCESS_SURFACE
    private val navCategory = setOf(CarAppInfo.CATEGORY_NAVIGATION)

    @Test
    fun categoryAndPermissionMakeItANavigationApp() {
        assertTrue(decideIsNavigation(navCategory, setOf(navTemplates, accessSurface)))
    }

    @Test
    fun categoryAloneIsNotEnough() {
        // Any app can declare the category in its manifest; only the permission request proves it.
        assertFalse(decideIsNavigation(navCategory, emptySet()))
        assertFalse(decideIsNavigation(navCategory, setOf(accessSurface)))
    }

    @Test
    fun permissionAloneWithoutTheCategoryIsNotEnough() {
        assertFalse(decideIsNavigation(emptySet(), setOf(navTemplates, accessSurface)))
    }

    @Test
    fun neitherMeansNotNavigation() {
        assertFalse(decideIsNavigation(emptySet(), emptySet()))
    }

    @Test
    fun otherCategoriesDoNotMatter() {
        val categories = setOf(CarAppInfo.CATEGORY_POI, CarAppInfo.CATEGORY_IOT)
        assertFalse(decideIsNavigation(categories, setOf(navTemplates)))
    }
}

class CarAppListingTest {
    private val service = "com.example.CarService"

    @Test
    fun ordinaryAppsAreListed() {
        assertTrue(listedAsCarApp("com.example", service, setOf(CarAppInfo.CATEGORY_NAVIGATION), googleAppEnabled = false))
        assertTrue(listedAsCarApp("com.example", service, emptySet(), googleAppEnabled = false))
    }

    @Test
    fun zoomMeetingServicesAreNever() {
        assertFalse(listedAsCarApp("us.zoom.videomeetings", "com.zipow.videobox.auto.ZmAutoMeetingService", emptySet(), true))
        assertFalse(listedAsCarApp("us.zoom.videomeetingsforgood", "com.zipow.videobox.auto.ZmAutoMeetingServiceV2", emptySet(), true))
        // Another service of the same app is.
        assertTrue(listedAsCarApp("us.zoom.videomeetings", "com.zipow.videobox.auto.OtherService", emptySet(), true))
    }

    @Test
    fun settingsScreensAreNotApps() {
        assertFalse(listedAsCarApp("com.example", service, setOf(CarAppInfo.CATEGORY_SETTINGS), googleAppEnabled = true))
    }

    @Test
    fun messagingAppsNeedTheGoogleApp() {
        val messaging = setOf(CarAppInfo.CATEGORY_MESSAGING)
        assertFalse(listedAsCarApp("com.example", service, messaging, googleAppEnabled = false))
        assertTrue(listedAsCarApp("com.example", service, messaging, googleAppEnabled = true))
    }
}
