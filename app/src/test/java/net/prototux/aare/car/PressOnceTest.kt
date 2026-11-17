package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

class PressOnceTest {
    private fun run(vararg downs: Boolean): List<Boolean> = PressOnce().let { k -> downs.map(k::pressed) }

    @Test
    fun actsOnTheFirstDownOnly() {
        assertEquals(listOf(true, false, false, false), run(true, true, true, false))
    }

    @Test
    fun actsOnTheUpWhenNoDownCame() {
        assertEquals(listOf(true, true), run(false, false))
    }

    @Test
    fun eachPressActsAgain() {
        assertEquals(listOf(true, false, true, false), run(true, false, true, false))
    }
}
