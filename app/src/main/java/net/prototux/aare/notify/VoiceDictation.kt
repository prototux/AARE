package net.prototux.aare.notify

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * One dictation for a voice reply (Settings.voiceReply), with the phone's speech recognizer: the
 * same one voice typing uses, so with *Car microphone for phone apps* (CarMicForApps) on it hears
 * the car's microphone like any app, and AARE handles no microphone itself. Main thread only.
 *
 * While listening it holds transient exclusive focus with the assistant usage, so the music pauses
 * (the car hears the cabin, not the speakers) and the car goes back to it afterwards. A recognizer
 * that hears no speech within [TIMEOUT_MS] is given up; once speech started it ends by itself when
 * the user stops talking, so a long reply isn't cut, and [MAX_MS] still bounds it: the music never
 * stays paused.
 */
internal class VoiceDictation(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onFailure: (Failure) -> Unit,
) {
    enum class Failure {
        /** Nothing understood: worth trying again. */
        NotHeard,
        /** No answer from the recognizer (network, server, busy, timeout): worth trying again. */
        TryAgain,
        /** No recognizer, no permission, or it broke: trying again won't help. */
        Unavailable,
    }

    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (recognizer == null) return@Runnable
        Log.w(TAG, "speech recognizer gave no answer in time")
        cancel()
        onFailure(Failure.TryAgain)
    }

    fun start() {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onFailure(Failure.Unavailable); return }
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) { onFailure(Failure.Unavailable); return }
        recognizer = r
        r.setRecognitionListener(listener)
        requestFocus()
        startedAt = SystemClock.uptimeMillis()
        handler.postDelayed(timeout, TIMEOUT_MS)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { r.startListening(intent) }.onFailure {
            Log.w(TAG, "speech recognizer refused to start", it)
            finish()
            onFailure(Failure.Unavailable)
        }
    }

    /** Stops listening without a result (left the screen, cancelled). */
    fun cancel() {
        recognizer?.let { runCatching { it.cancel() } }
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(timeout)
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        abandonFocus()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            // Speech started: the recognizer ends on the silence after it; only the overall limit stays.
            if (recognizer == null) return
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, MAX_MS - (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0))
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            firstText(partialResults)?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            val text = firstText(results)
            finish()
            if (text == null) onFailure(Failure.NotHeard) else onResult(text)
        }

        override fun onError(error: Int) {
            finish()
            onFailure(failureOf(error))
        }
    }

    private fun requestFocus() {
        if (focus != null) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        if (am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    companion object {
        private const val TAG = "AareVoiceReply"
        /** Longest wait for the user to start talking: the music is paused meanwhile. */
        const val TIMEOUT_MS = 15_000L
        /** Longest a dictation can last once speech started, so a long reply fits but nothing hangs. */
        const val MAX_MS = 60_000L

        private fun firstText(results: Bundle?): String? =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * Silence or nothing recognised: try again. A weak signal (in a tunnel, while driving), a
         * server or a busy recognizer: try again too. No permission, no recognizer, a broken
         * client: trying again won't help.
         */
        fun failureOf(error: Int): Failure = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Failure.NotHeard
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS -> Failure.TryAgain
            else -> Failure.Unavailable
        }

        // SpeechRecognizer.ERROR_SERVER_DISCONNECTED / ERROR_TOO_MANY_REQUESTS, API 31: inlined
        // values, so they're understood on any release.
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_TOO_MANY_REQUESTS = 10
    }
}
