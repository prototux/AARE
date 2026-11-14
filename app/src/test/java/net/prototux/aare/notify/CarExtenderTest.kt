package net.prototux.aare.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarExtenderTest {
    @Test
    fun aCompleteConversationIsUsable() {
        assertTrue(carExtenderUsable(arrayOf("Hi"), "Alice", hasReply = true, latestTimestamp = 1_000))
        // A null message is skipped when shown, not a reason to drop the conversation.
        assertTrue(carExtenderUsable(arrayOf(null, "Hi"), "Alice", hasReply = true, latestTimestamp = 1_000))
    }

    @Test
    fun missingPartsMakeItUnusable() {
        assertFalse(carExtenderUsable(null, "Alice", true, 1_000))
        assertFalse(carExtenderUsable(emptyArray<String>(), "Alice", true, 1_000))
        assertFalse(carExtenderUsable(arrayOf("Hi"), null, true, 1_000))
        assertFalse(carExtenderUsable(arrayOf("Hi"), "", true, 1_000))
        assertFalse(carExtenderUsable(arrayOf("Hi"), "Alice", false, 1_000))
        assertFalse(carExtenderUsable(arrayOf("Hi"), "Alice", true, 0))
    }

    @Test
    fun voiceMessagesWithoutTheirAudio() {
        assertTrue(isEmptyAudioMessage("audio/ogg", null))
        assertTrue(isEmptyAudioMessage("audio/ogg", ""))
        assertFalse(isEmptyAudioMessage("audio/ogg", "content://mms/part/1"))
        assertFalse(isEmptyAudioMessage("image/png", null))
        assertFalse(isEmptyAudioMessage(null, null))
    }
}
