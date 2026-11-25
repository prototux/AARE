package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageAppTest {
    private fun entry(id: String, pkg: String, kind: CarAppKind) = CarAppEntry(id, pkg, id, null, kind)
    private val apps = listOf(
        entry("media", "chat", CarAppKind.MEDIA),
        entry("messages", "chat", CarAppKind.MESSAGING),
        entry("launched", "other", CarAppKind.STANDALONE),
    )

    @Test
    fun aMessageOpensItsAppsConversationsElseTheAppElseNothing() {
        assertEquals("messages", messageApp(apps, "chat")?.id)
        assertEquals("launched", messageApp(apps, "other")?.id)
        assertEquals(null, messageApp(apps, "unknown"))
    }
}
