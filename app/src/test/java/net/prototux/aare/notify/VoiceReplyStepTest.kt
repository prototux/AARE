package net.prototux.aare.notify

import android.speech.SpeechRecognizer
import net.prototux.aare.notify.VoiceReplyStep.Companion.failure
import net.prototux.aare.notify.VoiceReplyStep.Companion.partial
import net.prototux.aare.notify.VoiceReplyStep.Companion.result
import net.prototux.aare.notify.VoiceReplyStep.Companion.retry
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceReplyStepTest {
    @Test
    fun whatIsHeardIsConfirmedBeforeSending() {
        var step: VoiceReplyStep = VoiceReplyStep.Listening()
        step = partial(step, "I'm on")
        assertEquals(VoiceReplyStep.Listening("I'm on"), step)
        step = result(step, " I'm on my way ")
        assertEquals(VoiceReplyStep.Confirm("I'm on my way"), step)
    }

    @Test
    fun lateRecognizerCallbacksDontChangeAConfirmation() {
        val confirm = VoiceReplyStep.Confirm("See you")
        assertEquals(confirm, partial(confirm, "something else"))
        assertEquals(confirm, result(confirm, "something else"))
        assertEquals(confirm, failure(confirm, VoiceDictation.Failure.Unavailable))
    }

    @Test
    fun blankOrSilenceCanBeTriedAgain() {
        assertEquals(VoiceReplyStep.NotHeard, result(VoiceReplyStep.Listening(), "  "))
        assertEquals(VoiceReplyStep.NotHeard, failure(VoiceReplyStep.Listening(), VoiceDictation.Failure.NotHeard))
        assertEquals(VoiceReplyStep.Listening(), retry(VoiceReplyStep.NotHeard))
        assertEquals(VoiceReplyStep.Listening(), retry(VoiceReplyStep.Confirm("Hi")))
        // A blank partial keeps what was already heard.
        assertEquals(VoiceReplyStep.Listening("Hi"), partial(VoiceReplyStep.Listening("Hi"), " "))
    }

    @Test
    fun noRecognitionOnlyOffersCancelling() {
        assertEquals(VoiceReplyStep.Unavailable, failure(VoiceReplyStep.Listening(), VoiceDictation.Failure.Unavailable))
        assertEquals(VoiceReplyStep.Unavailable, retry(VoiceReplyStep.Unavailable))
    }

    @Test
    fun recognizerErrors() {
        assertEquals(VoiceDictation.Failure.NotHeard, VoiceDictation.failureOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(VoiceDictation.Failure.NotHeard, VoiceDictation.failureOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceDictation.Failure.Unavailable, VoiceDictation.failureOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(VoiceDictation.Failure.Unavailable, VoiceDictation.failureOf(SpeechRecognizer.ERROR_CLIENT))
    }
}
