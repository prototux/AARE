package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

class LongPressKeyTest {
    private val events = ArrayList<String>()
    private val key = LongPressKey(onPress = { events += "press" }, onLongPress = { events += "long" })

    @Test
    fun shortPressFiresOnTheUp() {
        key.onKey(down = true, longPress = false)
        assertEquals(emptyList<String>(), events)
        key.onKey(down = false, longPress = false)
        assertEquals(listOf("press"), events)
    }

    @Test
    fun longPressDownFiresOnceAndItsUpIsNotAPress() {
        key.onKey(down = true, longPress = false)
        key.onKey(down = true, longPress = true)
        key.onKey(down = true, longPress = true) // repeats while held
        key.onKey(down = false, longPress = true)
        assertEquals(listOf("long"), events)
    }

    @Test
    fun longPressReportedOnlyOnTheUp() {
        key.onKey(down = true, longPress = false)
        key.onKey(down = false, longPress = true)
        assertEquals(listOf("long"), events)
    }

    @Test
    fun aPressAfterALongPressStillWorks() {
        key.onKey(down = true, longPress = true)
        key.onKey(down = false, longPress = false)
        key.onKey(down = true, longPress = false)
        key.onKey(down = false, longPress = false)
        assertEquals(listOf("long", "press"), events)
    }

    @Test
    fun aLostUpDoesNotSwallowTheNextPress() {
        key.onKey(down = true, longPress = true)
        // The up never arrived; the next press starts with a plain down.
        key.onKey(down = true, longPress = false)
        key.onKey(down = false, longPress = false)
        assertEquals(listOf("long", "press"), events)
    }
}
