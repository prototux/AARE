package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class HostedDpiTest {
    @Test
    fun aShortPaneGetsALowerDensityForSixHundredDp() {
        // 690 px high at 240 dpi would be 460 dp: 184 dpi makes it 600.
        assertEquals(184, hostedDpi(1550, 690, 240))
    }

    @Test
    fun aLargeDisplayKeepsTheCarDensity() {
        assertEquals(240, hostedDpi(1920, 1600, 240))
    }

    @Test
    fun neverBelowTheHelpersFloor() {
        assertEquals(120, hostedDpi(1550, 200, 240))
    }
}
