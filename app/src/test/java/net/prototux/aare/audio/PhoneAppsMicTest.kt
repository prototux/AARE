package net.prototux.aare.audio

import android.media.MediaRecorder.AudioSource
import net.prototux.aare.audio.MicTakeover.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAppsMicTest {
    private val aare = 10_123
    private val shell = 2000
    private val excluded = setOf(aare, shell)
    private val app = 10_456

    @Test
    fun voiceRecordingsOfOtherAppsAreTakenOver() {
        for (source in listOf(AudioSource.DEFAULT, AudioSource.MIC, AudioSource.VOICE_RECOGNITION, AudioSource.UNPROCESSED)) {
            assertTrue("source $source", PhoneAppsMic.takesOver(PhoneRecording(app, source, silenced = false), excluded))
        }
    }

    @Test
    fun callsCameraAndHotwordKeepThePhonesMic() {
        for (source in listOf(AudioSource.VOICE_COMMUNICATION, AudioSource.CAMCORDER, AudioSource.VOICE_PERFORMANCE,
            AudioSource.REMOTE_SUBMIX, 1999 /* hotword */)) {
            assertFalse("source $source", PhoneAppsMic.takesOver(PhoneRecording(app, source, silenced = false), excluded))
        }
    }

    @Test
    fun aareTheShellAndSilencedAppsAreLeftAlone() {
        assertFalse(PhoneAppsMic.takesOver(PhoneRecording(aare, AudioSource.VOICE_RECOGNITION, false), excluded))
        assertFalse(PhoneAppsMic.takesOver(PhoneRecording(shell, AudioSource.MIC, false), excluded))
        assertFalse(PhoneAppsMic.takesOver(PhoneRecording(app, AudioSource.MIC, silenced = true), excluded))
        // Uid unknown: counted as another app's.
        assertTrue(PhoneAppsMic.takesOver(PhoneRecording(-1, AudioSource.MIC, false), excluded))
    }

    @Test
    fun anyRecordingToTakeOverCounts() {
        assertFalse(PhoneAppsMic.anyTakesOver(emptyList(), excluded))
        assertFalse(PhoneAppsMic.anyTakesOver(listOf(PhoneRecording(aare, AudioSource.MIC, false),
            PhoneRecording(app, AudioSource.VOICE_COMMUNICATION, false)), excluded))
        assertTrue(PhoneAppsMic.anyTakesOver(listOf(PhoneRecording(app, AudioSource.VOICE_COMMUNICATION, false),
            PhoneRecording(app + 1, AudioSource.VOICE_RECOGNITION, false)), excluded))
    }

    @Test
    fun opensWhenAnAppRecordsAndClosesAfterTheHold() {
        val t = MicTakeover(holdMs = 1_000)
        assertEquals(Action.OPEN, t.onRecording(true, 0))
        assertTrue(t.open)
        assertEquals(-1L, t.nextTick())
        assertEquals(Action.NONE, t.onRecording(true, 100)) // already open
        assertEquals(Action.NONE, t.onRecording(false, 5_000))
        assertEquals(6_000L, t.nextTick())
        assertEquals(Action.NONE, t.onTick(5_999))
        assertEquals(Action.CLOSE, t.onTick(6_000))
        assertFalse(t.open)
        assertEquals(-1L, t.nextTick())
        assertEquals(Action.NONE, t.onTick(9_000))
    }

    @Test
    fun aRecordingThatRestartsWhenMovedToTheCarKeepsTheMicOpen() {
        val t = MicTakeover(holdMs = 1_000)
        t.onRecording(true, 0)
        assertEquals(Action.NONE, t.onRecording(false, 300))
        assertEquals(Action.NONE, t.onRecording(true, 450))
        assertEquals(-1L, t.nextTick())
        assertEquals(Action.NONE, t.onTick(1_300))
        assertTrue(t.open)
    }

    @Test
    fun theHoldCountsFromTheFirstStop() {
        val t = MicTakeover(holdMs = 1_000)
        t.onRecording(true, 0)
        t.onRecording(false, 100)
        t.onRecording(false, 600) // another app's update, still nothing recording
        assertEquals(1_100L, t.nextTick())
        assertEquals(Action.CLOSE, t.onTick(1_100))
    }

    @Test
    fun aRefusingCarIsntAskedAgainUntilANewRecording() {
        val t = MicTakeover(holdMs = 1_000)
        assertEquals(Action.OPEN, t.onRecording(true, 0))
        t.onRefused()
        assertFalse(t.open)
        assertEquals(Action.NONE, t.onRecording(true, 200)) // same recording, config update
        assertEquals(-1L, t.nextTick())
        assertEquals(Action.NONE, t.onRecording(false, 3_000))
        assertEquals(Action.OPEN, t.onRecording(true, 4_000))
    }

    @Test
    fun stopClosesOnlyAnOpenMic() {
        val t = MicTakeover()
        assertEquals(Action.NONE, t.stop())
        t.onRecording(true, 0)
        assertEquals(Action.CLOSE, t.stop())
        assertFalse(t.open)
        assertEquals(Action.OPEN, t.onRecording(true, 10))
    }
}
