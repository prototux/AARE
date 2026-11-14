package net.prototux.aare.car

import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService.TouchPad
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadNavigatorTest {
    private fun pad(w: Int, h: Int, pw: Int = 0, ph: Int = 0, sensitivity: Int? = null): TouchPad =
        TouchPad.newBuilder().setWidth(w).setHeight(h).setUiNavigation(true)
            .apply { if (pw != 0) setPhysicalWidth(pw); if (ph != 0) setPhysicalHeight(ph) }
            .apply { sensitivity?.let { setSensitivity(it) } }
            .build()

    @Test
    fun thresholdsAreASixthOfTheWiderSide() {
        // 10 units per mm: a sixth (166) beats 4 mm (40); the second step needs 4 mm more.
        assertEquals(TouchpadThresholds(166, 206), TouchpadThresholds.of(pad(1000, 500, 100, 50)))
        // Taller than wide: the height counts.
        assertEquals(TouchpadThresholds(100, 140), TouchpadThresholds.of(pad(300, 600, 30, 60)))
    }

    @Test
    fun thresholdsAreAtLeastFourMillimetres() {
        assertEquals(TouchpadThresholds(40, 80), TouchpadThresholds.of(pad(60, 40, 6, 4)))
    }

    @Test
    fun withoutAPhysicalSizeAUnitIsAMillimetre() {
        assertEquals(TouchpadThresholds(100, 104), TouchpadThresholds.of(pad(600, 300)))
    }

    @Test
    fun sensitivityScalesTheSteps() {
        assertEquals(TouchpadThresholds(166, 206), TouchpadThresholds.of(pad(1000, 500, 100, 50, sensitivity = 5)))
        assertEquals(TouchpadThresholds(166, 206), TouchpadThresholds.of(pad(1000, 500, 100, 50, sensitivity = 0)))
        // The most sensitive pad needs half the travel.
        assertEquals(TouchpadThresholds(83, 103), TouchpadThresholds.of(pad(1000, 500, 100, 50, sensitivity = 10)))
    }

    @Test
    fun aPadWithoutASizeGetsTheDefaults() {
        assertEquals(TouchpadThresholds.DEFAULT, TouchpadThresholds.of(null))
        assertEquals(TouchpadThresholds.DEFAULT, TouchpadThresholds.of(pad(0, 0)))
        assertEquals(TouchpadThresholds(48, 64), TouchpadThresholds.DEFAULT)
    }

    private val keys = ArrayList<Int>()
    private var focusMoves = true
    private val nav = TouchpadNavigator(TouchpadThresholds(100, 140)) { keys += it; focusMoves }

    private fun down(x: Int, y: Int) = nav.onEvent(MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
    private fun move(x: Int, y: Int) = nav.onEvent(MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat())
    private fun up(x: Int, y: Int) = nav.onEvent(MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())

    @Test
    fun aLongSwipeStepsItemByItemAndTheSecondStepIsLonger() {
        down(0, 0)
        assertFalse(move(50, 0))
        assertTrue(move(100, 5))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), keys)
        // The next step is measured from 100 and needs 140.
        assertFalse(move(230, 5))
        assertTrue(move(240, 5))
        // From then on, 100 again.
        assertFalse(move(330, 5))
        assertTrue(move(340, 5))
        assertEquals(List(3) { KeyEvent.KEYCODE_DPAD_RIGHT }, keys)
        up(340, 5)
    }

    @Test
    fun theFurthestDirectionWins() {
        down(0, 0)
        assertTrue(move(60, -100))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), keys)
        down(0, 0)
        assertTrue(move(-120, 80))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, keys.last())
        down(0, 0)
        assertTrue(move(100, 100)) // a tie goes vertical
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, keys.last())
    }

    @Test
    fun turningBackStartsOverFromThere() {
        down(0, 0)
        move(90, 0)
        // Back by 50, more than a third of a step: measured from 40 now.
        assertFalse(move(40, 0))
        assertFalse(move(-50, 0))
        assertTrue(move(-60, 0))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), keys)
    }

    @Test
    fun nothingToFocusMeasuresTheNextStepFromHere() {
        focusMoves = false
        down(0, 0)
        assertFalse(move(100, 0))
        assertFalse(move(150, 0))
        assertFalse(move(200, 0))
        assertEquals(List(2) { KeyEvent.KEYCODE_DPAD_RIGHT }, keys)
        // Still the first step of the swipe: the short threshold.
        focusMoves = true
        assertTrue(move(300, 0))
    }

    @Test
    fun eachSwipeStartsWithTheShortStep() {
        down(0, 0)
        assertTrue(move(0, 100))
        up(0, 100)
        down(0, 0)
        assertTrue(move(0, 100))
        assertEquals(List(2) { KeyEvent.KEYCODE_DPAD_DOWN }, keys)
    }

    @Test
    fun aSwipeWhoseDownWasLostStartsAtItsFirstMove() {
        assertFalse(move(500, 500))
        assertTrue(move(600, 500))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), keys)
    }

    @Test
    fun aTapMovesNothing() {
        down(10, 10)
        move(12, 11)
        up(12, 11)
        assertEquals(emptyList<Int>(), keys)
    }
}
