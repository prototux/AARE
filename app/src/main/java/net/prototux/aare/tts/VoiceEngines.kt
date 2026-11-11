package net.prototux.aare.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech

/** The phone's text-to-speech engines, as AARE's voice engine sees them. */
object VoiceEngines {
    /** Engines that sound good and are common, best first, used when the user hasn't picked one. */
    private val PREFERRED = listOf("com.google.android.tts", "com.samsung.SMT")

    class Engine(val packageName: String, val label: String)

    /** Every installed engine except AARE's own (delegating to ourselves would loop). */
    fun installed(context: Context): List<Engine> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.GET_META_DATA)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { Engine(it.packageName, it.loadLabel(pm).toString()) }
    }

    /** The engine that actually speaks: the user's choice if installed, else the best available. */
    fun delegate(context: Context, chosen: String?): Engine? {
        val all = installed(context)
        return all.firstOrNull { it.packageName == chosen }
            ?: PREFERRED.firstNotNullOfOrNull { p -> all.firstOrNull { it.packageName == p } }
            ?: all.firstOrNull()
    }

    /** Whether AARE is the phone's default voice engine (what navigation apps use unless they pick one). */
    fun aareIsDefault(context: Context): Boolean =
        runCatching { AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.TTS_DEFAULT_SYNTH) }.getOrNull() == context.packageName

    /** The system's text-to-speech settings, where the default engine is chosen. */
    fun settingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
