package net.prototux.aare.launch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchesPanelTest {
    @Test
    fun aDarkPanelAskedAgainIsDarkenedAgain() {
        // The power key lit it in between: without this the panel stayed lit for the rest of the session.
        assertTrue(switchesPanel(off = true, wasOff = true))
    }

    @Test
    fun aChangeSwitchesAndLitStaysLit() {
        assertTrue(switchesPanel(off = true, wasOff = false))
        assertTrue(switchesPanel(off = false, wasOff = true))
        assertFalse(switchesPanel(off = false, wasOff = false))
    }
}
