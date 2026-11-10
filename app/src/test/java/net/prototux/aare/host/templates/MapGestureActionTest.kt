package net.prototux.aare.host.templates

import org.junit.Assert.assertEquals
import org.junit.Test

class MapGestureActionTest {
    @Test
    fun singlePointerStartsADrag() {
        assertEquals(MapGestureAction.DRAG, mapGestureAction(pointerCount = 1, hadMultiplePointers = false))
    }

    @Test
    fun multiplePointersOnlyScale() {
        assertEquals(MapGestureAction.SCALE, mapGestureAction(pointerCount = 2, hadMultiplePointers = false))
        assertEquals(MapGestureAction.SCALE, mapGestureAction(pointerCount = 3, hadMultiplePointers = true))
    }

    @Test
    fun remainingPointerAfterPinchIsIgnored() {
        assertEquals(MapGestureAction.IGNORE, mapGestureAction(pointerCount = 1, hadMultiplePointers = true))
    }
}
