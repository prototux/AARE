package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeSideTest {
    @Test
    fun aSidewaysSwipeGivesItsSide() {
        assertEquals(true, swipeSide(-40f, 5f, 36f))
        assertEquals(false, swipeSide(60f, -10f, 36f))
    }

    @Test
    fun tooShortOrTooSteepIsNoSwipe() {
        assertNull(swipeSide(20f, 0f, 36f))
        assertNull(swipeSide(50f, 30f, 36f))
        assertNull(swipeSide(0f, 80f, 36f))
    }
}
