package net.prototux.aare.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time token that tells a root helper's broadcast from any other app's. */
class RootHandoverTest {
    @Test fun eachStartGetsItsOwnLongToken() {
        val a = RootHandover.newToken()
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, RootHandover.newToken())
    }

    @Test fun onlyTheExactTokenOpensIt() {
        val t = RootHandover.newToken()
        assertTrue(RootHandover.matches(t, t))
        assertFalse(RootHandover.matches(t, null))
        assertFalse(RootHandover.matches(t, ""))
        assertFalse(RootHandover.matches(t, t.dropLast(1)))
        assertFalse(RootHandover.matches(t, RootHandover.newToken()))
    }
}
