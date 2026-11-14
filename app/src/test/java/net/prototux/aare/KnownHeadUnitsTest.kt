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

    @Test fun garbageReadsAsUnknown() = assertEquals(null, KnownHeadUnits.decode("not json"))
}
