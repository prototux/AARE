package net.prototux.aare.ui

import net.prototux.aare.ui.car.movedAmong
import org.junit.Assert.assertEquals
import org.junit.Test

class DragSortTest {
    @Test
    fun aRowStepsOncePastHalfItsHeightAndNotOutOfTheList() {
        assertEquals(0, dragStep(40f, 100f, 1, 3))
        assertEquals(1, dragStep(51f, 100f, 1, 3))
        assertEquals(-1, dragStep(-51f, 100f, 1, 3))
        assertEquals(0, dragStep(90f, 100f, 2, 3))
        assertEquals(0, dragStep(-90f, 100f, 0, 3))
    }

    @Test
    fun itemsNotShownKeepTheirSlots() {
        assertEquals(listOf("c", "hidden", "a"), listOf("a", "hidden", "c").movedAmong(listOf("a", "c"), 0, 1))
        assertEquals(listOf("b", "a"), emptyList<String>().movedAmong(listOf("a", "b"), 0, 1))
        assertEquals(listOf("a", "hidden"), listOf("a", "hidden").movedAmong(listOf("a"), 0, 1))
    }
}
