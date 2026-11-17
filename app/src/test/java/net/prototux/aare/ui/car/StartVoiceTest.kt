package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartVoiceTest {
    private val steps = mutableListOf<String>()

    private val either = "$VOICE_BUTTON|$NEW_CHAT_BUTTON"

    private fun run(link: String, opens: Boolean = true, found: Boolean = true) =
        startVoice(link, { steps += "link"; opens }, { pattern, _ -> steps += pattern; found && pattern == either }) { steps += "shortcut"; true }

    @Test
    fun withoutALinkTheShortcut() {
        run("")
        assertEquals(listOf("shortcut"), steps)
    }

    @Test
    fun theFirstButtonShownIsTappedThenTheVoiceButtonLookedFor() {
        // A project's page shows "New session" (or the voice button); after a new chat, its voice button.
        run("https://example.com/project")
        assertEquals(listOf("link", either, VOICE_BUTTON), steps)
    }

    @Test
    fun noButtonFallsBackToTheShortcut() {
        run("https://example.com/project", found = false)
        assertEquals(listOf("link", either, "shortcut"), steps)
    }

    @Test
    fun aLinkThatDoesntOpenFallsBackToTheShortcut() {
        run("https://example.com/project", opens = false)
        assertEquals(listOf("link", "shortcut"), steps)
    }

    @Test
    fun voiceLabelMatchesTheButtonNotAChatLine() {
        val voice = Regex(VOICE_BUTTON, RegexOption.IGNORE_CASE)
        assertTrue(voice.containsMatchIn("Sprachmodus"))
        assertTrue(voice.containsMatchIn("Voice mode"))
        assertFalse(voice.containsMatchIn("Can you start voice mode later?"))
        val newChat = Regex(NEW_CHAT_BUTTON, RegexOption.IGNORE_CASE)
        assertTrue(newChat.containsMatchIn("Neue Sitzung"))
        assertFalse(newChat.containsMatchIn("Neue Sitzung starten?"))
    }
}
