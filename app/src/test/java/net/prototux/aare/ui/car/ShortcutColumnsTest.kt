package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutColumnsTest {
    @Test
    fun theSmallestSquareThatHoldsThem() {
        assertEquals(1, shortcutColumns(1))
        assertEquals(2, shortcutColumns(2))
        assertEquals(2, shortcutColumns(4))
        assertEquals(3, shortcutColumns(5))
        assertEquals(3, shortcutColumns(9))
        assertEquals(4, shortcutColumns(10))
        assertEquals(4, shortcutColumns(16))
    }

    @Test
    fun neverWiderThanFour() {
        assertEquals(4, shortcutColumns(17))
        assertEquals(4, shortcutColumns(40))
    }
}
