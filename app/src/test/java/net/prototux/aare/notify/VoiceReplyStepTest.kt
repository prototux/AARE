package net.prototux.aare.notify

import android.speech.SpeechRecognizer
import net.prototux.aare.notify.VoiceReplyStep.Answer
import net.prototux.aare.notify.VoiceReplyStep.Companion.answerOf
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
    fun aRecognizerThatDidntAnswerCanBeTriedAgain() {
        assertEquals(VoiceReplyStep.TryAgain, failure(VoiceReplyStep.Listening(), VoiceDictation.Failure.TryAgain))
        assertEquals(VoiceReplyStep.Listening(), retry(VoiceReplyStep.TryAgain))
        // While waiting for the spoken answer, a timeout just leaves the buttons.
        assertEquals(VoiceReplyStep.Confirm("Hi"), failure(VoiceReplyStep.Confirm("Hi"), VoiceDictation.Failure.TryAgain))
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
        // A weak signal (a tunnel), a server or a busy recognizer: worth trying again.
        assertEquals(VoiceDictation.Failure.TryAgain, VoiceDictation.failureOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(VoiceDictation.Failure.TryAgain, VoiceDictation.failureOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(VoiceDictation.Failure.TryAgain, VoiceDictation.failureOf(SpeechRecognizer.ERROR_SERVER))
        assertEquals(VoiceDictation.Failure.TryAgain, VoiceDictation.failureOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(VoiceDictation.Failure.TryAgain, VoiceDictation.failureOf(11)) // ERROR_SERVER_DISCONNECTED
        assertEquals(VoiceDictation.Failure.TryAgain, VoiceDictation.failureOf(10)) // ERROR_TOO_MANY_REQUESTS
        assertEquals(VoiceDictation.Failure.Unavailable, VoiceDictation.failureOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(VoiceDictation.Failure.Unavailable, VoiceDictation.failureOf(SpeechRecognizer.ERROR_CLIENT))
    }

    private val italianYes = listOf("sì", "si", "invia", "invialo", "ok")
    private val italianNo = listOf("no", "annulla", "non")

    @Test
    fun aSpokenYesSends() {
        assertEquals(Answer.Yes, answerOf("Yes", emptyList(), emptyList()))
        assertEquals(Answer.Yes, answerOf("ok send it", emptyList(), emptyList()))
        assertEquals(Answer.Yes, answerOf("Sì, invialo", italianYes, italianNo))
        // English always counts, whatever the language.
        assertEquals(Answer.Yes, answerOf("okay", italianYes, italianNo))
    }

    @Test
    fun aNoAnywhereWinsSoItNeverSendsByMistake() {
        assertEquals(Answer.No, answerOf("no", emptyList(), emptyList()))
        assertEquals(Answer.No, answerOf("No, don't send it", emptyList(), emptyList()))
        assertEquals(Answer.No, answerOf("Don\u2019t send it", emptyList(), emptyList()))
        assertEquals(Answer.No, answerOf("non inviarlo", italianYes, italianNo))
        assertEquals(Answer.No, answerOf("annulla", italianYes, italianNo))
    }

    @Test
    fun anythingElseLeavesItToTheButtons() {
        assertEquals(Answer.Unclear, answerOf("", emptyList(), emptyList()))
        assertEquals(Answer.Unclear, answerOf("what was that", emptyList(), emptyList()))
        // Whole words only: "sending" or "yesterday" aren't a yes, "know" isn't a no.
        assertEquals(Answer.Unclear, answerOf("yesterday I know", emptyList(), emptyList()))
        assertEquals(Answer.Unclear, answerOf("sending", emptyList(), emptyList()))
    }
}
