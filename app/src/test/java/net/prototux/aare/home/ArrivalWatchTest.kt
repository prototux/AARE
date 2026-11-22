package net.prototux.aare.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalWatchTest {
    private val home = Arrival(48.8566, 2.3522, radiusM = 150, enabled = true)
    private val places = mapOf("garage" to home)

    /** A point [northM] metres north of home. */
    private fun ArrivalWatch.at(northM: Double, accuracy: Float? = 10f) =
        update(home.latitude + northM / 111_195.0, home.longitude, accuracy, places)

    @Test
    fun distanceIsRoughlyRight() {
        val d = ArrivalWatch.distanceM(48.8566, 2.3522, 48.8566 + 1_000 / 111_195.0, 2.3522)
        assertEquals(1_000.0, d, 2.0)
    }

    @Test
    fun firesOnceWhenComingFromOutside() {
        val w = ArrivalWatch()
        assertTrue(w.at(2_000.0).isEmpty())
        assertTrue(w.at(500.0).isEmpty())
        assertEquals(listOf("garage"), w.at(100.0))
        // Parked, then the fix wobbles across the radius: no second arrival.
        assertTrue(w.at(20.0).isEmpty())
        assertTrue(w.at(180.0).isEmpty())
        assertTrue(w.at(120.0).isEmpty())
    }

    @Test
    fun firesAgainOnlyAfterLeavingBeyondTheExitRadius() {
        val w = ArrivalWatch()
        w.at(1_000.0)
        assertEquals(listOf("garage"), w.at(0.0))
        // 150 m radius: the exit radius is 250 m.
        assertEquals(250.0, ArrivalWatch.exitRadius(150), 0.0)
        assertTrue(w.at(240.0).isEmpty())
        assertTrue(w.at(10.0).isEmpty())
        w.at(300.0)
        assertEquals(listOf("garage"), w.at(10.0))
    }

    @Test
    fun aDriveThatStartsAtThePlaceIsNoArrival() {
        val w = ArrivalWatch()
        assertTrue(w.at(5.0).isEmpty())
        assertTrue(w.at(1_000.0).isEmpty())
        assertEquals(listOf("garage"), w.at(5.0))
    }

    @Test
    fun startingBetweenTheRadiiIsNoArrivalEither() {
        val w = ArrivalWatch()
        assertTrue(w.at(200.0).isEmpty())
        assertTrue(w.at(50.0).isEmpty())
    }

    @Test
    fun vagueFixesAreIgnored() {
        val w = ArrivalWatch()
        w.at(1_000.0)
        assertTrue(w.at(10.0, accuracy = 500f).isEmpty())
        assertEquals(listOf("garage"), w.at(10.0, accuracy = null))
    }
}
