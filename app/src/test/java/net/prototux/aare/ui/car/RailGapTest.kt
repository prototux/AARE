package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class RailGapTest {
    @Test
    fun theStandardSpaceInTheCarScreensPixels() {
        // The MBUX at 240 dpi: 4 dp between a side rail's icons are 6 px, 12 dp in a bottom bar 18 px.
        assertEquals(6, railGapPx(bottom = false, dpi = 240))
        assertEquals(18, railGapPx(bottom = true, dpi = 240))
        assertEquals(4, railGapPx(bottom = false, dpi = 160))
    }

    @Test
    fun roundedAsTheCarDrawsIt() {
        // 12 dp at 213 dpi are 15.98 px: the car draws 16.
        assertEquals(16, railGapPx(bottom = true, dpi = 213))
    }
}
