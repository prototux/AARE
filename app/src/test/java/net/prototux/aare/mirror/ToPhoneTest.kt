package net.prototux.aare.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToPhoneTest {
    @Test
    fun landscapePhoneOnAWideViewMapsAcrossItsBars() {
        // 2400x1080 shown in 1600x800: scale 2/3, 720 high, 40 bars top and bottom.
        assertEquals(1200f to 540f, toPhone(800f, 400f, 1600, 800, 2400, 1080))
        assertEquals(0f to 0f, toPhone(0f, 40f, 1600, 800, 2400, 1080))
    }

    @Test
    fun aTouchOnABarIsNothingUnlessClamped() {
        assertNull(toPhone(800f, 20f, 1600, 800, 2400, 1080))
        assertEquals(1200f to 0f, toPhone(800f, 20f, 1600, 800, 2400, 1080, clamp = true))
    }

    @Test
    fun portraitPhoneIsAColumnInTheMiddle() {
        // 1080x2400 in 1600x800: scale 1/3, 360 wide from x 620.
        assertNull(toPhone(100f, 400f, 1600, 800, 1080, 2400))
        assertEquals(540f to 1200f, toPhone(800f, 400f, 1600, 800, 1080, 2400))
    }
}
