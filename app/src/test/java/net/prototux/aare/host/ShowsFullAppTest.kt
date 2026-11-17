package net.prototux.aare.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowsFullAppTest {
    @Test
    fun parkedWithAHelperShowsTheChosenApp() {
        assertTrue(showsFullApp(chosen = true, driving = false, helper = true))
    }

    @Test
    fun drivingFallsBackToAaresView() {
        assertFalse(showsFullApp(chosen = true, driving = true, helper = true))
    }

    @Test
    fun noHelperOrNotChosenKeepsAaresView() {
        assertFalse(showsFullApp(chosen = true, driving = false, helper = false))
        assertFalse(showsFullApp(chosen = false, driving = false, helper = true))
    }
}
