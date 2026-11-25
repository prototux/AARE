package net.prototux.aare.car

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsCarKeyTest {
    @Test
    fun buttonsAreLogged() {
        // The voice button, to trace whether it reaches the phone at all.
        assertTrue(logsCarKey(KeyEvent.KEYCODE_SEARCH))
        assertTrue(logsCarKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(logsCarKey(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun knobAndTouchpadMovesAreNot() {
        assertFalse(logsCarKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(logsCarKey(KeyEvent.KEYCODE_NAVIGATE_NEXT))
        assertFalse(logsCarKey(KeyEvent.KEYCODE_ENTER))
    }
}
