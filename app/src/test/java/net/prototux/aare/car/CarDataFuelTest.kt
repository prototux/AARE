package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** vehicles.json forceElectric: EV override. */
class CarDataFuelTest {
    @Test fun noneOrUnknownBecomesElectric() {
        assertEquals(listOf(10), CarData.fuelTypes(emptyList(), forceElectric = true))
        assertEquals(listOf(10), CarData.fuelTypes(listOf(0), forceElectric = true))
    }

    @Test fun aReportedFuelIsKept() {
        assertEquals(listOf(1), CarData.fuelTypes(listOf(1), forceElectric = true))
        assertEquals(emptyList<Int>(), CarData.fuelTypes(emptyList(), forceElectric = false))
    }
}
