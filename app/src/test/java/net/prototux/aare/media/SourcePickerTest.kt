package net.prototux.aare.media

import androidx.media3.session.SessionError
import net.prototux.aare.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePickerTest {
    private fun ours(pkg: String, playing: Boolean = false, media: Boolean = true) =
        SourceCandidate("browser:$pkg", pkg, connected = true, playing = playing, hasMedia = media)

    private fun session(pkg: String, playing: Boolean = false, media: Boolean = true) =
        SourceCandidate("session:$pkg", pkg, connected = false, playing = playing, hasMedia = media)

    @Test
    fun nothing() {
        assertNull(pickSource(emptyList(), null))
        assertNull(pickSource(listOf(ours("a", media = false), session("b", media = false)), null))
    }

    @Test
    fun playingSessionBeatsPausedConnection() {
        assertEquals("session:b", pickSource(listOf(ours("a"), session("b", playing = true)), "browser:a"))
    }

    @Test
    fun playingConnectionWins() {
        assertEquals("browser:a", pickSource(listOf(ours("a", playing = true), session("b", playing = true)), "session:b"))
    }

    @Test
    fun amongPlayingSessionsKeepPrevious() {
        val c = listOf(session("b", playing = true), session("c", playing = true))
        assertEquals("session:c", pickSource(c, "session:c"))
        assertEquals("session:b", pickSource(c, null))
    }

    @Test
    fun pausingDoesNotJumpToAnotherApp() {
        val c = listOf(ours("a"), session("b"))
        assertEquals("session:b", pickSource(c, "session:b"))
        assertEquals("browser:a", pickSource(c, null))
    }

    @Test
    fun connectionHidesItsOwnPhoneSession() {
        val c = listOf(session("a", playing = true), ours("a", playing = false))
        assertEquals("browser:a", pickSource(c, "session:a"))
    }

    @Test
    fun phoneSessionUsedWhileConnectionHasNothing() {
        val c = listOf(session("a", playing = true), ours("a", media = false))
        assertEquals("session:a", pickSource(c, null))
    }

    @Test
    fun errorTextMapsSessionErrors() {
        assertEquals(R.string.media_error_sign_in, errorTextRes(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED))
        assertEquals(R.string.media_error_disconnected, errorTextRes(SessionError.ERROR_SESSION_DISCONNECTED))
        assertEquals(R.string.media_error_code, errorTextRes(12345))
    }

    @Test
    fun timeFormat() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_400))
        assertEquals("1:02:03", formatTime(3_723_000))
    }
}
