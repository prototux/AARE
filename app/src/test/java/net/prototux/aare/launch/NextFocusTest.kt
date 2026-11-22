package net.prototux.aare.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class NextFocusTest {
    @Test
    fun anotherHostedAppOnTopKeepsTheFocus() {
        assertEquals(7, nextFocus(listOf(5, 7, 0, 0), gone = 5))
    }

    @Test
    fun thePhoneWhenItIsNext() {
        assertEquals(0, nextFocus(listOf(5, 0, 7), gone = 5))
    }

    @Test
    fun theTopDisplayWhenTheGoneOneIsEmpty() {
        assertEquals(0, nextFocus(listOf(0, 7), gone = 5))
    }

    @Test
    fun thePhoneWhenNothingElseIsLeft() {
        assertEquals(0, nextFocus(listOf(5), gone = 5))
        assertEquals(0, nextFocus(emptyList(), gone = 5))
    }
}
