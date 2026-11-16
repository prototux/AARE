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
}
