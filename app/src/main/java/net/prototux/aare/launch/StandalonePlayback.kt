package net.prototux.aare.launch

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import net.prototux.aare.notify.CarNotificationListener

/**
 * Keeps the parked-only launched apps paused while driving, through their media sessions: their
 * screen is hidden, and their sound must stop too, including a next track or video that starts on
 * its own. Each session of those apps is paused when driving starts and again whenever it starts
 * playing until driving ends.
 *
 * Needs notification access, as AARE's media already does; without it, or for an app with no media
 * session, only the screen is hidden. Main thread only.
 */
class StandalonePlaybackGuard(context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, CarNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val onChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        // A call already queued on the handler can arrive after the guard stopped listening.
        if (listening) watch(list.orEmpty())
    }
    private var listening = false
    private var paused: Set<String> = emptySet()
    private val watched = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    /** Keeps [packages] paused, pausing now what they play; empty (parked, or none) lets them play. */
    fun keepPaused(packages: Set<String>) {
        paused = packages
        if (packages.isEmpty()) {
            release()
            return
        }
        runCatching {
            if (!listening) {
                manager.addOnActiveSessionsChangedListener(onChanged, listener, handler)
                listening = true
            }
            watch(manager.getActiveSessions(listener))
        }.onFailure { Log.w(TAG, "can't keep launched apps paused: ${it.message}") }
    }

    /** Follows the sessions of the paused apps among [controllers], drops those that ended. */
    private fun watch(controllers: List<MediaController>) {
        val active = controllers.mapTo(HashSet()) { it.sessionToken }
        watched.keys.filter { it !in active }.forEach { token -> watched.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        for (c in controllers) {
            if (c.packageName !in paused) continue
            pauseIfPlaying(c)
            if (c.sessionToken in watched) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (c.packageName in paused) pauseIfPlaying(c)
                }
            }
            c.registerCallback(callback, handler)
            watched[c.sessionToken] = c to callback
        }
    }

    private fun pauseIfPlaying(c: MediaController) {
        val state = c.playbackState?.state ?: return
        if (state !in PLAYING) return
        runCatching { c.transportControls.pause() }.onFailure { Log.w(TAG, "could not pause ${c.packageName}: ${it.message}") }
    }

    /** Stops following sessions: nothing is paused any more. */
    fun release() {
        paused = emptySet()
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(onChanged) }
        listening = false
        watched.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
    }

    private companion object {
        const val TAG = "AareStandalone"

        /** States in which the app is (about to be) heard. */
        val PLAYING = setOf(
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        )
    }
}
