package net.prototux.aare.car

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyInjectorTest {
    /** A scheduler on a fake clock: [advance] runs what came due. */
    private class FakeScheduler : KeyInjector.Scheduler {
        var now = 0L
        val tasks = ArrayList<Pair<Long, Runnable>>()
        override fun postDelayed(task: Runnable, delayMs: Long) { tasks += now + delayMs to task }
        override fun cancel(task: Runnable) { tasks.removeAll { it.second === task } }
        fun advance(ms: Long) {
            now += ms
            tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second.run() }
        }
    }

    private val clock = FakeScheduler()
    private val keys = ArrayList<CarKey>()
    private val injector = KeyInjector(500, clock) { keys += it }

    private val shiftOn = KeyEvent.META_SHIFT_ON

    @Test
    fun aShortPressIsADownAndAnUp() {
        injector.onKey(KeyEvent.KEYCODE_ENTER, down = true, metaState = 0, carLongPress = false)
        clock.advance(100)
        injector.onKey(KeyEvent.KEYCODE_ENTER, down = false, metaState = 0, carLongPress = false)
        clock.advance(1000)
        assertEquals(listOf(CarKey(KeyEvent.KEYCODE_ENTER, true), CarKey(KeyEvent.KEYCODE_ENTER, false)), keys)
    }

    @Test
    fun aHeldKeyGetsALongPressAfterTheTimeout() {
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = false)
        clock.advance(499)
        assertEquals(1, keys.size)
        clock.advance(1)
        assertEquals(CarKey(KeyEvent.KEYCODE_HOME, true, repeat = 1, longPress = true), keys[1])
        injector.onKey(KeyEvent.KEYCODE_HOME, down = false, metaState = 0, carLongPress = false)
        assertEquals(CarKey(KeyEvent.KEYCODE_HOME, false), keys[2])
        clock.advance(1000)
        assertEquals("the long press fires once", 3, keys.size)
    }

    @Test
    fun carRepeatsCountUpAndTheLongPressStillComes() {
        injector.onKey(KeyEvent.KEYCODE_VOLUME_UP, down = true, metaState = 0, carLongPress = false)
        clock.advance(100)
        injector.onKey(KeyEvent.KEYCODE_VOLUME_UP, down = true, metaState = 0, carLongPress = false)
        injector.onKey(KeyEvent.KEYCODE_VOLUME_UP, down = true, metaState = 0, carLongPress = false)
        assertEquals(listOf(0, 1, 2), keys.map { it.repeat })
        clock.advance(400)
        assertEquals(CarKey(KeyEvent.KEYCODE_VOLUME_UP, true, repeat = 1, longPress = true), keys.last())
        injector.onKey(KeyEvent.KEYCODE_VOLUME_UP, down = true, metaState = 0, carLongPress = false)
        assertEquals(3, keys.last().repeat)
    }

    @Test
    fun theUpCancelsTheLongPress() {
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = false)
        clock.advance(300)
        injector.onKey(KeyEvent.KEYCODE_HOME, down = false, metaState = 0, carLongPress = false)
        clock.advance(1000)
        assertTrue(keys.none { it.longPress })
        // The next press starts its own hold.
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = false)
        assertEquals(0, keys.last().repeat)
        clock.advance(500)
        assertTrue(keys.last().longPress)
    }

    @Test
    fun keysAreHeldSeparately() {
        injector.onKey(KeyEvent.KEYCODE_A, down = true, metaState = 0, carLongPress = false)
        clock.advance(300)
        injector.onKey(KeyEvent.KEYCODE_B, down = true, metaState = 0, carLongPress = false)
        injector.onKey(KeyEvent.KEYCODE_A, down = false, metaState = 0, carLongPress = false)
        clock.advance(500)
        assertEquals(CarKey(KeyEvent.KEYCODE_B, true, repeat = 1, longPress = true), keys.last())
    }

    @Test
    fun theCarsOwnLongPressComesThroughOnceAndReplacesTheTimer() {
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = false)
        clock.advance(300)
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = true)
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = true)
        clock.advance(1000)
        injector.onKey(KeyEvent.KEYCODE_HOME, down = false, metaState = 0, carLongPress = true)
        assertEquals(
            listOf(
                CarKey(KeyEvent.KEYCODE_HOME, true),
                CarKey(KeyEvent.KEYCODE_HOME, true, repeat = 1, longPress = true),
                CarKey(KeyEvent.KEYCODE_HOME, true, repeat = 2),
                CarKey(KeyEvent.KEYCODE_HOME, false, longPress = true),
            ),
            keys,
        )
    }

    @Test
    fun aLongPressAfterTheTimerFiredIsJustARepeat() {
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = false)
        clock.advance(500)
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = true)
        assertEquals(1, keys.count { it.longPress })
    }

    @Test
    fun metastateGoesThroughExceptOnNavigationKeys() {
        injector.onKey(KeyEvent.KEYCODE_A, down = true, metaState = shiftOn, carLongPress = false)
        assertEquals(shiftOn, keys.last().metaState)
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyInjector.KEYCODE_ROTARY_CONTROLLER,
        )) {
            injector.onKey(code, down = true, metaState = shiftOn, carLongPress = false)
            assertEquals("keycode $code", 0, keys.last().metaState)
            injector.onKey(code, down = false, metaState = shiftOn, carLongPress = false)
            assertEquals("keycode $code", 0, keys.last().metaState)
        }
        // The long press keeps the first down's metastate.
        clock.advance(500)
        assertEquals(CarKey(KeyEvent.KEYCODE_A, true, shiftOn, repeat = 1, longPress = true), keys.last())
    }

    @Test
    fun clearDropsEveryHold() {
        injector.onKey(KeyEvent.KEYCODE_HOME, down = true, metaState = 0, carLongPress = false)
        injector.clear()
        clock.advance(1000)
        assertEquals(1, keys.size)
    }
}
