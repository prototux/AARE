package net.prototux.aare.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Answers apps that ask an engine which languages it has installed (`CHECK_TTS_DATA`) before they
 * use it. AARE speaks with another engine, so it reports every language: if the real engine lacks
 * one, the prompt itself will fail and say so, instead of the app refusing AARE up front.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voices = Locale.getAvailableLocales()
            .mapNotNull { l -> runCatching { if (l.country.isEmpty()) l.isO3Language else "${l.isO3Language}-${l.isO3Country}" }.getOrNull() }
            .filter { it.isNotEmpty() }
            .distinct()
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(voices))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList()))
        finish()
    }
}
