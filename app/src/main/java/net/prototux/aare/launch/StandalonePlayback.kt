package net.prototux.aare.launch

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.util.Log
import net.prototux.aare.notify.CarNotificationListener

/**
 * Pauses what the launched app [pkg] plays, through its media sessions: a parked-only app is paused
 * while driving, not just hidden. Needs notification access, as AARE's media already does; without
 * it, or for an app with no media session, only its screen is hidden.
 */
fun pauseStandalonePlayback(context: Context, pkg: String) {
    runCatching {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
        manager.getActiveSessions(ComponentName(context, CarNotificationListener::class.java))
            .filter { it.packageName == pkg }
            .forEach { it.transportControls.pause() }
    }.onFailure { Log.w("AareStandalone", "could not pause $pkg: ${it.message}") }
}
