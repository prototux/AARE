package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarDataTest {

    @Test
    fun energyModelChargeIsStoredOverCapacity() {
        assertEquals(50f, CarData.EnergyModel(30_000, 60_000, null, null, null).batteryPercent!!, 0.01f)
        assertNull(CarData.EnergyModel(30_000, null, null, null, null).batteryPercent)
        assertNull(CarData.EnergyModel(30_000, 0, null, null, null).batteryPercent)
    }
}
