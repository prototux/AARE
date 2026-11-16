package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class BarButtonPositionTest {
    @Test
    fun sideBarAnchorsButtonToSameSide() {
        assertEquals(0f, barButtonDefaultX(railBottom = false, railOnRight = false))
        assertEquals(1f, barButtonDefaultX(railBottom = false, railOnRight = true))
    }

    @Test
    fun bottomBarCentersButton() {
        assertEquals(0.5f, barButtonDefaultX(railBottom = true, railOnRight = false))
        assertEquals(0.5f, barButtonDefaultX(railBottom = true, railOnRight = true))
    }
}
