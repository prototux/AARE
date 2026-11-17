package net.prototux.aare.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaysMediaTest {
    @Test
    fun aMessengerWithAMediaBrowserIsNoMediaApp() {
        // Signal and Molly declare Android Auto messages, and have a media browser for voice notes.
        assertFalse(AndroidAutoApps.staysMedia(declaresCarApp = true) { setOf("notification") })
    }

    @Test
    fun mediaAppsStay() {
        assertTrue(AndroidAutoApps.staysMedia(declaresCarApp = true) { setOf("media", "notification") })
        assertTrue(AndroidAutoApps.staysMedia(declaresCarApp = false) { emptySet() })
    }
}
