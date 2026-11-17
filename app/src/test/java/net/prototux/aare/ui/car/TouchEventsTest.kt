package net.prototux.aare.ui.car

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchEventsTest {
    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val a = Finger(0, 10f, 10f)
    private val b = Finger(1, 50f, 50f)

    @Test
    fun oneFingerTapsAsBefore() {
        assertEquals(listOf(Touch(MotionEvent.ACTION_DOWN, listOf(a))), touchEvents(emptyList(), listOf(a)))
        val moved = a.copy(x = 20f)
        assertEquals(listOf(Touch(MotionEvent.ACTION_MOVE, listOf(moved))), touchEvents(listOf(a), listOf(moved)))
        assertEquals(listOf(Touch(MotionEvent.ACTION_UP, listOf(moved))), touchEvents(listOf(moved), emptyList()))
    }

    @Test
    fun aPinchAddsTheSecondFingerAndLiftsItAgain() {
        assertEquals(listOf(Touch(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(a, b))), touchEvents(listOf(a), listOf(a, b)))
        val apart = listOf(a.copy(x = 0f), b.copy(x = 80f))
        assertEquals(listOf(Touch(MotionEvent.ACTION_MOVE, apart)), touchEvents(listOf(a, b), apart))
        // The second finger lifts first, then the other is the last one up.
        assertEquals(listOf(Touch(pointer(MotionEvent.ACTION_POINTER_UP, 1), apart)), touchEvents(apart, listOf(apart[0])))
        assertEquals(listOf(Touch(MotionEvent.ACTION_UP, listOf(apart[0]))), touchEvents(listOf(apart[0]), emptyList()))
    }

    @Test
    fun theLiftedFingerIsAtItsIndexAmongThoseDown() {
        val events = touchEvents(listOf(a, b), listOf(b))
        assertEquals(1, events.size)
        val up = events[0]
        assertEquals(MotionEvent.ACTION_POINTER_UP, up.action and MotionEvent.ACTION_MASK)
        val index = (up.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
        assertEquals(a, up.fingers[index])
        assertTrue(b in up.fingers)
    }

    @Test
    fun nothingChangedSendsNothing() {
        assertEquals(emptyList<Touch>(), touchEvents(listOf(a, b), listOf(a, b)))
    }
}
