package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAutoAppsTest {
    private fun app(pkg: String, declaresMedia: Boolean = false) = PhoneApp(pkg, pkg, null, declaresMedia)

    /** #156: Signal's voice-note player is a media browser, but Signal is a messaging app. */
    @Test
    fun messagingAppWithAMediaBrowserIsNotAMediaApp() {
        assertEquals(setOf("org.thoughtcrime.securesms"), AndroidAutoApps.messagingOnly(listOf(app("org.thoughtcrime.securesms"))))
    }

    @Test
    fun messagingAppDeclaringMediaStaysAMediaAppToo() {
        assertEquals(emptySet<String>(), AndroidAutoApps.messagingOnly(listOf(app("com.example.chat", declaresMedia = true))))
    }

    @Test
    fun onlyTheMessagingAppsAreKeptOutOfTheMediaList() {
        val messaging = listOf(app("com.example.sms"), app("com.example.chat", declaresMedia = true), app("com.example.voice"))
        assertEquals(setOf("com.example.sms", "com.example.voice"), AndroidAutoApps.messagingOnly(messaging))
    }
}
