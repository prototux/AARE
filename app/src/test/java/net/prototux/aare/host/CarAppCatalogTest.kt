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
        assertTrue(listedAsCarApp("com.example", service, setOf(CarAppInfo.CATEGORY_NAVIGATION)))
        assertTrue(listedAsCarApp("com.example", service, emptySet()))
    }

    @Test
    fun zoomMeetingServicesAreNever() {
        assertFalse(listedAsCarApp("us.zoom.videomeetings", "com.zipow.videobox.auto.ZmAutoMeetingService", emptySet()))
        assertFalse(listedAsCarApp("us.zoom.videomeetingsforgood", "com.zipow.videobox.auto.ZmAutoMeetingServiceV2", emptySet()))
        // Another service of the same app is.
        assertTrue(listedAsCarApp("us.zoom.videomeetings", "com.zipow.videobox.auto.OtherService", emptySet()))
    }

    @Test
    fun settingsScreensAreNotApps() {
        assertFalse(listedAsCarApp("com.example", service, setOf(CarAppInfo.CATEGORY_SETTINGS)))
    }

    @Test
    fun messagingAppsAreListedWithoutTheGoogleApp() {
        // AARE runs on phones without the Google app.
        assertTrue(listedAsCarApp("com.example", service, setOf(CarAppInfo.CATEGORY_MESSAGING)))
    }
}
