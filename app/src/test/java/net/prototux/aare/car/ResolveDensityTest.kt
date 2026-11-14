package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** F14: the car screen's density, in precedence order (vehicle override > phone setting > head unit > default). */
class ResolveDensityTest {
    @Test
    fun vehicleOverrideWinsOverEverything() {
        assertEquals(300, CarSession.resolveDensity(vehicleDpi = 300, settingDpi = 160, headUnitDensity = 240, shortSide = 720))
    }

    @Test
    fun phoneSettingWinsWithNoVehicleOverride() {
        assertEquals(180, CarSession.resolveDensity(vehicleDpi = null, settingDpi = 180, headUnitDensity = 240, shortSide = 720))
    }

    @Test
    fun autoSettingFallsBackToTheHeadUnitsOwnValue() {
        // 0 is "Auto": neither a vehicle override nor a fixed phone choice.
        assertEquals(240, CarSession.resolveDensity(vehicleDpi = null, settingDpi = 0, headUnitDensity = 240, shortSide = 720))
    }

    @Test
    fun autoWithNoHeadUnitValueFallsBackToTheSizeDefault() {
        assertEquals(CarSession.defaultDensity(720), CarSession.resolveDensity(vehicleDpi = null, settingDpi = 0, headUnitDensity = 0, shortSide = 720))
        assertEquals(CarSession.defaultDensity(1440), CarSession.resolveDensity(vehicleDpi = null, settingDpi = 0, headUnitDensity = 0, shortSide = 1440))
    }
}
