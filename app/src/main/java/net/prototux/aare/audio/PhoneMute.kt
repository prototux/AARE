package net.prototux.aare.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import net.prototux.aare.DiagLog

/**
 * Keeps the phone's media volume at 0 while the car plays a copy of its sound.
 *
 * Screen-capture audio copies what apps play before the phone's volume is applied (checked in the
 * car: media volume 0 leaves the car's sound unchanged), but it can't stop the phone playing it
 * too. Muting the phone's media volume silences the original only. The volume to restore is saved
 * first, so a drive that never ended cleanly (the app killed while connected) is undone by
 * [recover] at the next start, like [net.prototux.aare.car.DrivingMode].
 */
class PhoneMute(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val prefs = state(context)

    /** Mutes ([on]) or restores the phone's media volume. Idempotent. Main thread. */
    fun set(on: Boolean) {
        if (on) mute() else restore(am, prefs, "car audio: phone media volume restored")
    }

    private fun mute() {
        if (prefs.contains(KEY_VOLUME)) return
        val v = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
        // Already silent: nothing to undo later.
        if (v == 0) return
        // Saved first (commit, not apply): if the app dies now, the next start puts it back.
        prefs.edit().putInt(KEY_VOLUME, v).commit()
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
            .onSuccess { DiagLog.add("car audio: phone media volume muted while the car plays (was $v)") }
            .onFailure { prefs.edit().remove(KEY_VOLUME).apply(); DiagLog.add("car audio: can't mute the phone: ${it.message}") }
    }

    companion object {
        private const val KEY_VOLUME = "media_volume_before_car"

        /** Puts back a media volume left muted by a drive that never ended. Never while a session runs. */
        fun recover(context: Context) = restore(context.getSystemService(AudioManager::class.java), state(context),
            "car audio: phone media volume left muted by an earlier drive, restored")

        private fun state(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences("phone_mute", Context.MODE_PRIVATE)

        private fun restore(am: AudioManager, prefs: SharedPreferences, why: String) {
            if (!prefs.contains(KEY_VOLUME)) return
            val v = prefs.getInt(KEY_VOLUME, 0)
            prefs.edit().remove(KEY_VOLUME).apply()
            // Only if it's still the 0 we set: the user turning it up meanwhile wins.
            runCatching {
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    DiagLog.add(why)
                }
            }
        }
    }
}
