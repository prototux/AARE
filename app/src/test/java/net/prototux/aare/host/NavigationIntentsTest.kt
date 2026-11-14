package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationIntentsTest {
    private val navigate = "androidx.car.app.action.NAVIGATE"
    private val view = "android.intent.action.VIEW"
    private val search = "android.intent.action.SEARCH"

    private fun cal(action: String?, data: String?, alias: Boolean = false) = toCarAppNavigation(action, data, alias, wazeFormat = false)
    private fun waze(action: String?, data: String?) = toCarAppNavigation(action, data, false, wazeFormat = true)

    @Test
    fun recognisesNavigationData() {
        assertTrue(isNavigationData("geo:48.8584,2.2945"))
        assertTrue(isNavigationData("google.navigation:q=Lyon"))
        assertTrue(isNavigationData("geo.action:?act=mute"))
        assertTrue(isNavigationData("https://maps.google.com/maps?q=Lyon"))
        assertFalse(isNavigationData("https://www.google.com/maps?q=Lyon"))
        assertFalse(isNavigationData("tel:123"))
        assertFalse(isNavigationData(null))
    }

    @Test
    fun aCarAppRequestBecomesAMapsUrl() {
        assertEquals("https://maps.google.com/maps?nav=1&q=Eiffel%20Tower", mapsUrlOf("geo:0,0?q=Eiffel%20Tower"))
        assertEquals("https://maps.google.com/maps?nav=1&q=48.8584,2.2945", mapsUrlOf("geo:48.8584,2.2945"))
        assertEquals("https://maps.google.com/maps?nav=1&q=48.8584,2.2945", mapsUrlOf("geo:0,0?q=48.8584,2.2945"))
        assertEquals("https://maps.google.com/maps?nav=1&q=Rue+de+Rivoli", mapsUrlOf("geo:0,0?q=Rue de Rivoli"))
        assertNull(mapsUrlOf("geo:0,0?z=3"))
    }

    @Test
    fun theCarAppLibraryFormatIsLeftAlone() {
        val geo = "geo:0,0?q=Lyon"
        assertEquals(NavigationRequest(navigate, geo), cal(navigate, geo))
        assertEquals(NavigationRequest(navigate, geo), waze(navigate, geo))
        assertEquals(NavigationRequest(navigate, "geo:45.76,4.83"), cal(navigate, "geo:45.76,4.83"))
        assertEquals(NavigationRequest(view, "geo.action:?act=mute"), waze(view, "geo.action:?act=mute"))
    }

    @Test
    fun aHomeOrWorkRequestIsLeftAlone() {
        assertEquals(NavigationRequest(navigate, "google.navigation:q=home"), cal(navigate, "google.navigation:q=home", alias = true))
    }

    @Test
    fun otherFormatsBecomeGeoForCarApps() {
        assertEquals(NavigationRequest(navigate, "geo:0,0?q=Eiffel%20Tower"), cal(view, "https://maps.google.com/maps?nav=1&q=Eiffel%20Tower"))
        assertEquals(NavigationRequest(navigate, "geo:48.8584,2.2945"), cal(view, "https://maps.google.com/maps?nav=1&q=48.8584,2.2945"))
        assertEquals(NavigationRequest(navigate, "geo:0,0?q=Foo+Bar"), cal(null, "google.navigation:q=Foo+Bar"))
        assertEquals(NavigationRequest(navigate, "geo:0,0?q=Rue+de+Rivoli"), cal(view, "https://maps.google.com/maps?saddr=Here&daddr=Rue+de+Rivoli"))
    }

    @Test
    fun aRoundTripKeepsTheDestination() {
        // What a car app's "navigate to" reaches the navigation app as: geo: → Maps URL → geo:.
        for (geo in listOf("geo:0,0?q=Eiffel%20Tower", "geo:48.8584,2.2945")) {
            assertEquals(geo, cal(view, mapsUrlOf(geo)).data)
            assertEquals(geo, waze(view, mapsUrlOf(geo)).data)
        }
    }

    @Test
    fun wazeGetsSearchesAvoidsAndCommands() {
        assertEquals(NavigationRequest(search, "geo:0,0?q=Pizza"), waze(view, "https://maps.google.com/maps?q=Pizza&entry=sar"))
        assertEquals(NavigationRequest(navigate, "geo:0,0?q=Lyon", avoid = "th"), waze(view, "https://maps.google.com/maps?daddr=Lyon&dirflg=ht"))
        assertEquals(
            NavigationRequest(view, "geo:0,0?act=report_hazard&hazard_type=pothole"),
            waze(view, "https://maps.google.com/maps?act=report_hazard&hazard_type=pothole&foo=1"),
        )
        // Other car apps get none of that: a plain navigation.
        assertEquals(NavigationRequest(navigate, "geo:0,0?q=Pizza"), cal(view, "https://maps.google.com/maps?q=Pizza&entry=sar"))
    }

    @Test
    fun aPlaceNameWithActInItIsNoWazeCommand() {
        assertEquals(NavigationRequest(navigate, "geo:0,0?q=Factory%20Lane"), waze(view, "https://maps.google.com/maps?nav=1&q=Factory%20Lane"))
    }

    @Test
    fun anOpaqueUriIsANavigationForWaze() {
        assertEquals(NavigationRequest(navigate, "geo:0,0?q=Lyon"), waze(null, "google.navigation:q=Lyon&mode=d"))
    }

    @Test
    fun otherIntentsAreLeftAlone() {
        assertEquals(NavigationRequest(view, "https://example.com/?q=1"), cal(view, "https://example.com/?q=1"))
        assertEquals(NavigationRequest(navigate, null), cal(navigate, null))
    }
}
