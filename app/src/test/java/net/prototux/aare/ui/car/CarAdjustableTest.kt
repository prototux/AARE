package net.prototux.aare.ui.car

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class CarAdjustableTest {
    private fun key(code: Int, adjusting: Boolean, shift: Boolean = false, vertical: Boolean = false) =
        carAdjustKey(code, shift, vertical, adjusting)

    @Test
    fun selectStartsAndStopsAdjusting() {
        assertEquals(CarAdjustKey(adjusting = true), key(KeyEvent.KEYCODE_DPAD_CENTER, adjusting = false))
        assertEquals(CarAdjustKey(adjusting = false), key(KeyEvent.KEYCODE_DPAD_CENTER, adjusting = true))
        assertEquals(CarAdjustKey(adjusting = true), key(KeyEvent.KEYCODE_ENTER, adjusting = false))
    }

    @Test
    fun notAdjustingTheKnobAndDpadStillMoveTheFocus() {
        val passOn = CarAdjustKey(adjusting = false, consumed = false)
        for (code in listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP)) {
            assertEquals(passOn, key(code, adjusting = false))
        }
    }

    @Test
    fun adjustingTheKnobTurnSteps() {
        assertEquals(CarAdjustKey(adjusting = true, step = 1), key(KeyEvent.KEYCODE_TAB, adjusting = true))
        assertEquals(CarAdjustKey(adjusting = true, step = -1), key(KeyEvent.KEYCODE_TAB, adjusting = true, shift = true))
    }

    @Test
    fun adjustingTheDpadAlongTheControlSteps() {
        assertEquals(1, key(KeyEvent.KEYCODE_DPAD_RIGHT, adjusting = true).step)
        assertEquals(-1, key(KeyEvent.KEYCODE_DPAD_LEFT, adjusting = true).step)
        assertEquals(1, key(KeyEvent.KEYCODE_DPAD_UP, adjusting = true, vertical = true).step)
        assertEquals(-1, key(KeyEvent.KEYCODE_DPAD_DOWN, adjusting = true, vertical = true).step)
    }

    @Test
    fun theDpadAcrossTheControlStopsAdjustingAndMovesTheFocusOn() {
        val leave = CarAdjustKey(adjusting = false, consumed = false)
        assertEquals(leave, key(KeyEvent.KEYCODE_DPAD_UP, adjusting = true))
        assertEquals(leave, key(KeyEvent.KEYCODE_DPAD_RIGHT, adjusting = true, vertical = true))
    }

    @Test
    fun otherKeysAreNotUsedAndKeepTheAdjusting() {
        assertEquals(CarAdjustKey(adjusting = true, consumed = false), key(KeyEvent.KEYCODE_BACK, adjusting = true))
    }
}
