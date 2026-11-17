package net.prototux.aare.ui.car

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchEventsMoreTest {
    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val a = Finger(0, 10f, 10f)
    private val b = Finger(1, 50f, 50f)

    @Test
    fun aFingerLiftedWhileTheOtherMovesStaysInTheMoveUntilItsUp() {
        // The case Android dropped: the move named only the finger left down, the lifted one still counted.
        val moved = a.copy(x = 20f)
        assertEquals(
            listOf(Touch(MotionEvent.ACTION_MOVE, listOf(moved, b)), Touch(pointer(MotionEvent.ACTION_POINTER_UP, 1), listOf(moved, b))),
            touchEvents(listOf(a, b), listOf(moved)),
        )
    }

    @Test
    fun aFingerLiftedAsAnotherLandsLiftsFirst() {
        val c = Finger(2, 90f, 90f)
        assertEquals(
            listOf(Touch(pointer(MotionEvent.ACTION_POINTER_UP, 1), listOf(a, b)), Touch(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(a, c))),
            touchEvents(listOf(a, b), listOf(a, c)),
        )
    }

    @Test
    fun everyEventNamesTheFingersDownThen() {
        // A whole pinch: each event's fingers are those down after the event before.
        val steps = listOf(listOf(a), listOf(a, b), listOf(a.copy(x = 0f), b.copy(x = 80f)), listOf(b.copy(x = 90f)), emptyList())
        var down = emptyList<Int>()
        steps.zipWithNext { x, y -> touchEvents(x, y) }.flatten().let { listOf(Touch(MotionEvent.ACTION_DOWN, listOf(a))) + it }.forEach { t ->
            val action = t.action and MotionEvent.ACTION_MASK
            val ids = t.fingers.map { it.id }
            when (action) {
                MotionEvent.ACTION_DOWN -> { assertEquals(emptyList<Int>(), down); down = ids }
                MotionEvent.ACTION_POINTER_DOWN -> { assertEquals(down.toSet() + ids.last(), ids.toSet()); down = ids }
                else -> assertEquals(down.toSet(), ids.toSet())
            }
            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
                val index = (t.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
                down = ids - t.fingers[index].id
            }
        }
        assertEquals(emptyList<Int>(), down)
    }
}
