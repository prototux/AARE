package net.prototux.aare.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowsMessageTest {
    @Test
    fun aMutedAppsMessagesStayOutAndGroupsFollowTheirSwitch() {
        assertTrue(showsMessage("chat", group = false, groups = false, muted = emptySet()))
        assertFalse(showsMessage("chat", group = false, groups = true, muted = setOf("chat")))
        assertFalse(showsMessage("chat", group = true, groups = false, muted = setOf("other")))
        assertTrue(showsMessage("chat", group = true, groups = true, muted = setOf("other")))
    }
}
