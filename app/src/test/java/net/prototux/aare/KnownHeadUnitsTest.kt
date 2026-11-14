package net.prototux.aare

import net.prototux.aare.car.HeadUnitIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/** A head unit's identity kept between sessions (vehicles.json `wireless`). */
class KnownHeadUnitsTest {
    @Test fun anIdentityRoundTrips() {
        val id = HeadUnitIdentity("Pioneer", "AVH", "Pioneer AVH Pioneer 9inch Display Audio", "2021", "Pioneer", "9inch Display Audio", "B1", 1 to 6)
        assertEquals(id, KnownHeadUnits.decode(KnownHeadUnits.encode(id)))
        val noVersion = id.copy(protocol = null)
        assertEquals(noVersion, KnownHeadUnits.decode(KnownHeadUnits.encode(noVersion)))
    }

    @Test fun aRuleLeavesWirelessOutOnlyForAHeadUnitItNeverWorkedWith() {
        val id = HeadUnitIdentity("Pioneer", "AVH", "Pioneer AVH", headUnitMake = "ALPINE", headUnitModel = "iLX-705D")
        assertEquals(id, KnownHeadUnits.skipWireless(worked = false, known = id) { true })
        assertEquals(null, KnownHeadUnits.skipWireless(worked = true, known = id) { true })
        assertEquals(null, KnownHeadUnits.skipWireless(worked = false, known = id) { false })
        // Never seen over USB: nothing to go by.
        assertEquals(null, KnownHeadUnits.skipWireless(worked = false, known = null) { true })
    }

    @Test fun theHeadUnitsSeenLongestAgoGoFirst() {
        assertEquals(emptySet<String>(), KnownHeadUnits.oldest(mapOf("A" to 1L, "B" to 2L), max = 2))
        assertEquals(setOf("A", "C"), KnownHeadUnits.oldest(mapOf("A" to 1L, "B" to 5L, "C" to 0L, "D" to 3L), max = 2))
    }

    @Test fun garbageReadsAsUnknown() = assertEquals(null, KnownHeadUnits.decode("not json"))
}
