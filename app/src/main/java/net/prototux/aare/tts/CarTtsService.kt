package net.prototux.aare.tts

import android.media.AudioFormat
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.aap.pcm.PacedPcmQueue
import net.prototux.aare.aap.pcm.PcmConvert
import net.prototux.aare.aap.pcm.Wav
import java.util.Locale

/**
 * AARE as a text-to-speech engine, so navigation apps' voice reaches the car without root.
 *
 * Android only lets a normal app capture media, game and "unknown" sounds, never navigation
 * guidance. But apps that speak through the phone's voice engine (CoMaps, Organic Maps, OsmAnd...)
 * hand their prompts to whatever engine the user chose. With AARE chosen, each prompt is spoken by
 * the user's real engine ([DelegateEngine]), then:
 *  - with a car connected, sent to the car's navigation guidance channel ([CarVoice]), where the
 *    car ducks its own sound around it like with Android Auto, and nothing plays on the phone;
 *  - otherwise played on the phone like any engine does.
 *
 * Requests arrive one at a time on the framework's synthesis thread.
 */
class CarTtsService : TextToSpeechService() {
    private var engine: DelegateEngine? = null
    @Volatile private var stopped = false
    /** The language last loaded, as the ISO 3 codes the framework asks about. */
    @Volatile private var loaded = arrayOf("", "", "")

    override fun onCreate() {
        engine = newEngine()
        super.onCreate()
    }

    override fun onDestroy() {
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    private fun newEngine(): DelegateEngine? {
        val chosen = VoiceEngines.delegate(this, Settings(this).voiceEngine) ?: run {
            DiagLog.add("car voice: no other voice engine installed to speak with")
            return null
        }
        return DelegateEngine(this, chosen.packageName)
    }

    /** The delegate, recreated if the user picked another engine meanwhile. */
    private fun engine(): DelegateEngine? {
        val wanted = VoiceEngines.delegate(this, Settings(this).voiceEngine)?.packageName
        val current = engine
        if (current != null && current.packageName == wanted) return current
        current?.shutdown()
        return newEngine().also { engine = it }
    }

    // ------------------------------------------------------------------ languages and voices

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        engine()?.isLanguageAvailable(locale(lang, country, variant)) ?: TextToSpeech.LANG_NOT_SUPPORTED

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        // Called on the main thread while the delegate binds: answer "available" rather than
        // "missing" then, or apps would ask the user to install voice data.
        val answer = if (result == TextToSpeech.LANG_NOT_SUPPORTED && engine()?.languages.isNullOrEmpty()) TextToSpeech.LANG_AVAILABLE else result
        if (answer >= TextToSpeech.LANG_AVAILABLE) loaded = arrayOf(lang.orEmpty(), country.orEmpty(), variant.orEmpty())
        return answer
    }

    override fun onGetLanguage(): Array<String> = loaded

    override fun onGetVoices(): List<Voice> = engine()?.voices?.toList().orEmpty()

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName != null && engine()?.voices?.any { it.name == voiceName } == true) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val want = locale(lang, country, variant)
        val voices = engine()?.voices.orEmpty()
        // Prefer voices that work offline: a prompt mustn't wait for the network.
        fun pick(match: (Voice) -> Boolean) = voices.filter(match).minByOrNull { if (it.isNetworkConnectionRequired) 1 else 0 }?.name
        return pick { it.locale == want }
            ?: pick { it.locale.isO3LanguageOrEmpty() == want.isO3LanguageOrEmpty() && it.locale.country == want.country }
            ?: pick { it.locale.isO3LanguageOrEmpty() == want.isO3LanguageOrEmpty() }
    }

    // ------------------------------------------------------------------ speech

    override fun onStop() {
        stopped = true
        CarVoice.current?.flush()
        engine?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val started = SystemClock.elapsedRealtime()
        val wav = engine()?.synthesize(
            request.charSequenceText ?: "",
            locale(request.language, request.country, request.variant),
            request.voiceName,
            request.speechRate / 100f,
            request.pitch / 100f,
        ) { stopped }
        if (wav == null) {
            if (!stopped) { DiagLog.add("car voice: the voice engine didn't speak"); callback.error(TextToSpeech.ERROR_SYNTHESIS) }
            else callback.done()
            return
        }
        val synthMs = SystemClock.elapsedRealtime() - started
        val car = CarVoice.current
        if (car != null) toCar(wav, car, synthMs, callback) else onPhone(wav, callback)
    }

    /** To the car's guidance channel; the phone stays quiet. Returns once the car has played it. */
    private fun toCar(wav: Wav, car: PacedPcmQueue, synthMs: Long, callback: SynthesisCallback) {
        val pcm = PcmConvert.convert(wav.pcm, wav.format, car.format)
        // The framework wants a format before done(); nothing follows, so nothing plays here.
        callback.start(car.format.rate, AudioFormat.ENCODING_PCM_16BIT, car.format.channels)
        car.push(pcm)
        val playNs = car.format.nanosFor(pcm.size)
        // Report done when the car has had it, so the app's next prompt or its own ducking lines up.
        val drained = car.awaitDrained(playNs + DRAIN_SLACK_NS) { stopped }
        DiagLog.add("car voice: ${playNs / 1_000_000} ms prompt to the car (spoken in $synthMs ms${if (!drained && !stopped) ", car slow" else ""})")
        callback.done()
    }

    /** No car: play it here, like any engine. */
    private fun onPhone(wav: Wav, callback: SynthesisCallback) {
        if (callback.start(wav.format.rate, AudioFormat.ENCODING_PCM_16BIT, wav.format.channels) != TextToSpeech.SUCCESS) return
        val max = callback.maxBufferSize
        var at = 0
        while (at < wav.pcm.size && !stopped) {
            val n = minOf(max, wav.pcm.size - at)
            if (callback.audioAvailable(wav.pcm, at, n) != TextToSpeech.SUCCESS) break
            at += n
        }
        callback.done()
    }

    private companion object {
        /** Margin over the prompt's length for the car's acks before giving up waiting. */
        const val DRAIN_SLACK_NS = 3_000_000_000L

        fun locale(lang: String?, country: String?, variant: String?) = Locale(lang.orEmpty(), country.orEmpty(), variant.orEmpty())

        fun Locale.isO3LanguageOrEmpty(): String = runCatching { isO3Language }.getOrDefault(language)
    }
}
