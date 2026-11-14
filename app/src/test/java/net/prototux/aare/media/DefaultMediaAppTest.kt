package net.prototux.aare.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultMediaAppTest {
    @Test
    fun picksTheFirstInstalledInPriorityOrder() {
        assertEquals("com.pandora.android", pickDefaultMediaApp(listOf("com.pandora.android", "com.amazon.mp3")))
        assertEquals("com.spotify.music", pickDefaultMediaApp(listOf("com.amazon.mp3", "com.spotify.music")))
    }

    @Test
    fun nullWhenNoneOfTheDefaultsAreInstalled() {
        assertNull(pickDefaultMediaApp(listOf("com.example.other")))
        assertNull(pickDefaultMediaApp(emptyList()))
    }
}
