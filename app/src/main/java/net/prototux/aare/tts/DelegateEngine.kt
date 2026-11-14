package net.prototux.aare.tts

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import net.prototux.aare.aap.pcm.Wav
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Another engine on the phone (Google's, Samsung's...) doing the actual speaking for AARE's voice
 * engine. Speech comes back as a WAV file through `synthesizeToFile`, the only public way to get an
 * engine's audio instead of having it play. The file is written whole before it's read: engines
 * fix the WAV header at the end, so they need a seekable file, not a pipe.
 *
 * Calls block until the engine is bound, except on the main thread, where they never wait: the
 * TTS framework calls in from there while the service starts, and the bind result is delivered on
 * that same thread.
 */
class DelegateEngine(private val context: Context, val packageName: String?) {
    private val bound = CountDownLatch(1)
    @Volatile private var ok = false
    private val done = ConcurrentHashMap<String, CountDownLatch>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val ids = AtomicInteger()
    /** The locale last given to the engine, so it isn't set again for every prompt. */
    private var language: Locale? = null

    private val tts: TextToSpeech = TextToSpeech(context, { status ->
        ok = status == TextToSpeech.SUCCESS
        if (!ok) Log.w(TAG, "engine $packageName didn't start ($status)")
        bound.countDown()
    }, packageName).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { done[id]?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { failed += id; done[id]?.countDown() }
            override fun onError(id: String, errorCode: Int) { failed += id; done[id]?.countDown() }
            override fun onStop(id: String, interrupted: Boolean) { failed += id; done[id]?.countDown() }
        })
    }

    /** The bound engine, or null if it failed or isn't bound yet (never waits on the main thread). */
    private fun ready(): TextToSpeech? {
        if (Looper.myLooper() == Looper.getMainLooper()) return if (bound.count == 0L && ok) tts else null
        return if (bound.await(BIND_TIMEOUT_S, TimeUnit.SECONDS) && ok) tts else null
    }

    fun isLanguageAvailable(locale: Locale): Int =
        ready()?.let { runCatching { it.isLanguageAvailable(locale) }.getOrNull() } ?: TextToSpeech.LANG_NOT_SUPPORTED

    val voices: Set<Voice> get() = ready()?.let { runCatching { it.voices }.getOrNull() } ?: emptySet()

    val languages: Set<Locale> get() = ready()?.let { runCatching { it.availableLanguages }.getOrNull() } ?: emptySet()

    /**
     * Speaks [text] into PCM. [rate] and [pitch] are the request's (1.0 = normal). Null if the
     * engine failed, timed out, or [cancelled] turned true meanwhile.
     */
    @Synchronized
    fun synthesize(text: CharSequence, locale: Locale?, voiceName: String?, rate: Float, pitch: Float, cancelled: () -> Boolean): Wav? {
        val t = ready() ?: return null
        val voice = voiceName?.let { name -> runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == name } }
        if (voice != null) {
            if (runCatching { t.voice?.name }.getOrNull() != voice.name) t.voice = voice
            language = voice.locale
        } else if (locale != null && locale != language) {
            t.language = locale
            language = locale
        }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        val id = "aare-${ids.incrementAndGet()}"
        val file = File(context.cacheDir, "car-voice-$id.wav")
        val latch = CountDownLatch(1)
        done[id] = latch
        try {
            if (t.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) return null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SYNTH_TIMEOUT_S)
            while (!latch.await(50, TimeUnit.MILLISECONDS)) {
                if (cancelled() || System.nanoTime() > deadline) { runCatching { t.stop() }; return null }
            }
            if (failed.remove(id)) return null
            return Wav.parse(file.readBytes()).also { if (it == null) Log.w(TAG, "engine $packageName wrote audio AARE can't read") }
        } catch (e: Exception) {
            Log.w(TAG, "synthesis with $packageName failed", e)
            return null
        } finally {
            done.remove(id)
            failed.remove(id)
            file.delete()
        }
    }

    fun stop() { runCatching { tts.stop() } }

    fun shutdown() { runCatching { tts.shutdown() } }

    private companion object {
        const val TAG = "AareCarVoice"
        const val BIND_TIMEOUT_S = 5L
        /** Long enough for a long prompt from a slow (network) engine. */
        const val SYNTH_TIMEOUT_S = 20L
    }
}
