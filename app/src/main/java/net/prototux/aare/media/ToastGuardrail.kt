package net.prototux.aare.media

import android.os.SystemClock

/**
 * Browser-service components known to resend the same non-fatal playback error often enough to
 * spam the car with toasts: their errors are rate-limited (see [ToastGuardrail]) instead of every
 * one becoming a notice.
 */
val TOAST_GUARDRAIL_COMPONENTS = setOf(
    "com.google.android.apps.youtube.music/com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService",
    "com.bbc.sounds/com.bbc.sounds.playback.platform.AndroidMediaService",
    "com.extreamsd.usbaudioplayerpro/com.extreamsd.usbaudioplayershared.MediaPlaybackService",
    "com.mixcloud.player/com.mixcloud.codaplayer.service.CodaPlayerService",
    "com.audioaddict.di/md5625afa336d6bcf709cd87fc4cca812d5.PlaybackService",
)

/** The shipped defaults: at most 3 toasts per window, and a window of 0 ms, which never limits. */
const val TOAST_GUARDRAIL_MAX_POSTS = 3
const val TOAST_GUARDRAIL_WINDOW_MS = 0L

/**
 * Caps how often a non-fatal playback-error toast repeats for the same component: at most [max]
 * in any [windowMs]. A [windowMs] of 0 (the shipped default for [TOAST_GUARDRAIL_COMPONENTS])
 * never limits. Not thread-safe: use it from one thread (the main thread).
 */
class ToastGuardrail(private val max: Int = TOAST_GUARDRAIL_MAX_POSTS, private val windowMs: Long = TOAST_GUARDRAIL_WINDOW_MS) {
    private val times = HashMap<String, ArrayDeque<Long>>()

    /** Records a toast for [component] at [now] (elapsedRealtime) and tells whether it's still allowed. */
    fun allow(component: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (windowMs <= 0) return true
        val queue = times.getOrPut(component) { ArrayDeque() }
        while (queue.isNotEmpty() && now - queue.first() > windowMs) queue.removeFirst()
        queue.addLast(now)
        return queue.size <= max
    }
}
