package net.prototux.aare.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutForTest {
    @Test
    fun findsTheVoiceShortcutAmongTheAppsOwn() {
        assertEquals("quick_action_voice", shortcutFor(listOf("quick_action_new_chat", "quick_action_voice", "quick_action_code"), "voice"))
    }

    @Test
    fun ignoresCase() {
        assertEquals("StartVoiceMode", shortcutFor(listOf("NewChat", "StartVoiceMode"), "voice"))
    }

    @Test
    fun noneWithoutAMatch() {
        assertNull(shortcutFor(listOf("new_chat", "code"), "voice"))
        assertNull(shortcutFor(emptyList(), "voice"))
    }
}
