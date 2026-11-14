package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationQueryTest {
    @Test
    fun aCommaBetweenHouseNumberAndPostcodeBecomesASpace() {
        assertEquals("Weteringschans 6-8 1017 SG Amsterdam", navigationQuery("Weteringschans 6-8, 1017 SG Amsterdam"))
        assertEquals("12 3000 Leuven", navigationQuery("12,3000 Leuven"))
    }

    @Test
    fun otherCommasStay() {
        assertEquals("Rue de Rivoli 99, Paris", navigationQuery("Rue de Rivoli 99, Paris"))
        assertEquals("Café de Flore, 172 Boulevard Saint-Germain", navigationQuery("Café de Flore, 172 Boulevard Saint-Germain"))
    }

    @Test
    fun coordinatesStayCoordinates() {
        assertEquals("48.8584,2.2945", navigationQuery("48.8584,2.2945"))
        assertEquals("-33.8568, 151.2153", navigationQuery(" -33.8568, 151.2153 "))
    }

    @Test
    fun geoUriEncodesLikeAndroidUri() {
        assertEquals("geo:0,0?q=Caf%C3%A9%20de%20Flore%2C%20172%20Boulevard%20Saint-Germain",
            geoQueryUri("Café de Flore, 172 Boulevard Saint-Germain"))
        // Uri.encode keeps these unescaped; & and = must not split the query.
        assertEquals("a-b_c.d!e~f*g'h(i)j%26k%3Dl%2Fm%3Fn", encodeUriComponent("a-b_c.d!e~f*g'h(i)j&k=l/m?n"))
    }

    @Test
    fun geoUriKeepsCoordinatesAndFixesHouseNumberCommas() {
        assertEquals("geo:0,0?q=48.8584%2C2.2945", geoQueryUri("48.8584,2.2945"))
        assertEquals("geo:0,0?q=Weteringschans%206-8%201017%20SG%20Amsterdam", geoQueryUri("Weteringschans 6-8, 1017 SG Amsterdam"))
    }
}
