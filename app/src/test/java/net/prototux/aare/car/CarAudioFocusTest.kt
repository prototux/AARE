package net.prototux.aare.car

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType.AUDIO_FOCUS_GAIN
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType.AUDIO_FOCUS_RELEASE
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import net.prototux.aare.aap.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What AARE asks the car for around its music and navigation voice, and what it takes as a grant. */
class CarAudioFocusTest {
    private var now = 0L
    private val sent = mutableListOf<Pair<AudioFocusRequestType, Int>>()
    private val tasks = mutableMapOf<Runnable, Long>()
    private val scheduler = object : CarAudioFocus.Scheduler {
        override fun at(timeMs: Long, task: Runnable) { tasks[task] = timeMs }
        override fun cancel(task: Runnable) { tasks.remove(task) }
    }
    private val focus = CarAudioFocus({ type, priority -> sent += type to priority }, scheduler) { now }

    /** Moves the clock to [ms], running what falls due on the way, as the main handler would. */
    private fun advanceTo(ms: Long) {
        while (true) {
            val (task, at) = tasks.entries.filter { it.value <= ms }.minByOrNull { it.value }?.toPair() ?: break
            tasks.remove(task)
            now = at
            task.run()
        }
        now = ms
    }

    private fun car(state: AudioFocusStateType, unsolicited: Boolean = false, quirk: Boolean = false) =
        focus.onCarFocus(AudioFocusNotification.newBuilder().setFocusState(state).setUnsolicited(unsolicited).build(), quirk)

    private fun sentSince(from: Int) = sent.drop(from).map { it.first }

    @Test fun musicAsksForFullFocusAndReleasesItAfter() {
        focus.media.request(true)
        focus.media.request(false)
        assertEquals(listOf(AUDIO_FOCUS_GAIN to Priority.CONTROL, AUDIO_FOCUS_RELEASE to Priority.AUDIO), sent)
    }

    @Test fun aPromptDucksThenHandsTheFocusBackToTheMusic() {
        focus.media.request(true)
        focus.guidance.request(true)
        focus.guidance.request(false)
        assertEquals(listOf(AUDIO_FOCUS_GAIN to Priority.CONTROL, AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK to Priority.CONTROL,
            AUDIO_FOCUS_GAIN to Priority.AUDIO), sent)
    }

    @Test fun musicEndingDuringAPromptLeavesTheFocusToThePrompt() {
        focus.media.request(true)
        focus.guidance.request(true)
        val before = sent.size
        focus.media.request(false)
        assertEquals(emptyList<AudioFocusRequestType>(), sentSince(before))
        focus.guidance.request(false)
        assertEquals(listOf(AUDIO_FOCUS_RELEASE), sentSince(before))
    }

    @Test fun aLossAnsweringAPromptIsAGrantForIt() {
        // The Ford answers the may-duck request with LOSS: the music lost focus so the prompt plays.
        focus.guidance.request(true)
        car(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS)
        assertTrue(focus.guidance.granted)
        assertFalse(focus.media.granted)
        // A call or the car's own source taking over is no grant.
        focus.guidance.request(true)
        car(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT)
        assertFalse(focus.guidance.granted)
    }

    @Test fun aTransientGainAnsweringAPromptIsNotForTheMusic() {
        focus.guidance.request(true)
        car(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT)
        assertTrue(focus.guidance.granted)
        assertFalse(focus.media.granted)
        // Unless the car is known to mean music too.
        car(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, quirk = true)
        assertTrue(focus.media.granted)
    }

    @Test fun aTransientGainTheCarDecidesItselfLetsMusicPlay() {
        car(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, unsolicited = true)
        assertTrue(focus.media.granted)
    }

    @Test fun withoutAnAnswerItAsksAgainThenReleasesFirst() {
        focus.media.request(true)
        val before = sent.size
        advanceTo(1_000)
        assertEquals(listOf(AUDIO_FOCUS_GAIN), sentSince(before))
        assertEquals(Priority.AUDIO, sent.last().second)
        advanceTo(3_000)
        assertEquals(listOf(AUDIO_FOCUS_GAIN, AUDIO_FOCUS_RELEASE, AUDIO_FOCUS_GAIN), sentSince(before))
    }

    @Test fun anAnswerStopsTheAsking() {
        focus.media.request(true)
        car(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
        val before = sent.size
        advanceTo(60_000)
        assertEquals(emptyList<AudioFocusRequestType>(), sentSince(before))
        assertTrue(focus.media.granted)
    }

    @Test fun itDoesNotAskForTheMusicOverAPrompt() {
        focus.media.request(true)
        focus.guidance.request(true)
        val before = sent.size
        advanceTo(1_500)
        assertEquals(emptyList<AudioFocusRequestType>(), sentSince(before))
    }

    @Test fun afterACallItReleasesThenAsksAgainAndKeepsAsking() {
        focus.askAgainAfterCall()
        assertEquals(listOf(AUDIO_FOCUS_RELEASE to Priority.AUDIO, AUDIO_FOCUS_GAIN to Priority.AUDIO), sent)
        advanceTo(1_000)
        assertEquals(AUDIO_FOCUS_GAIN, sent.last().first)
    }

    @Test fun aClosedSessionAsksNoMore() {
        focus.media.request(true)
        val check = tasks.keys.single()
        focus.close()
        assertTrue(tasks.isEmpty())
        // Even a check already on its way does nothing.
        val before = sent.size
        now = 1_000
        check.run()
        assertEquals(before, sent.size)
    }
}
