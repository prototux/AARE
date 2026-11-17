package net.prototux.aare.ui.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowsMediaTest {
    private val kinds = mapOf("music" to CarAppKind.MEDIA, "map" to CarAppKind.NAVIGATION)
    private fun kind(id: String) = kinds[id]

    @Test
    fun aMediaAppOnScreenHoldsTheStream() {
        assertTrue(CarScreen.App("music").showsMedia(::kind))
    }

    @Test
    fun otherScreensDont() {
        assertFalse(CarScreen.App("map").showsMedia(::kind))
        assertFalse(CarScreen.Dashboard.showsMedia(::kind))
        assertFalse(CarScreen.Launcher.showsMedia(::kind))
    }
}
