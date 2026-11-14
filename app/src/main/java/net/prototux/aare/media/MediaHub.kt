package net.prototux.aare.media

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** A media app found on the phone (media3 MediaLibraryService or legacy MediaBrowserService). */
data class MediaApp(
    val packageName: String,
    val service: ComponentName,
    val label: String,
    val icon: Bitmap?,
)

/** What is playing, from whichever media app is active. */
data class PlaybackInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** SystemClock.elapsedRealtime() when positionMs was sampled, to extrapolate progress. */
    val sampledAtMs: Long,
)

/**
 * Media side of the car: discovery, one connection to the active media app, transport controls,
 * and the full-screen browse/now-playing UI.
 *
 * Implemented in this package. All methods are called on the main thread.
 */
interface MediaHub {
    /** Media apps installed on the phone; refreshed on package changes. */
    val apps: StateFlow<List<MediaApp>>

    /** The current playback, from the connected app or any active media session. */
    val playback: StateFlow<PlaybackInfo?>

    /** Connects to [app] (if not already) and makes it the active one. */
    fun select(app: MediaApp)

    /**
     * Waits until the connection to the selected app is ready, at most [timeoutMs]. Also true when the app is
     * connected but its library did not load in time; false if it couldn't connect, there is none, or it timed out.
     * The default, for hubs without a connection to wait for, is false.
     */
    suspend fun awaitConnection(timeoutMs: Long): Boolean = false

    fun playPause()
    fun next()
    fun previous()
    fun pause()

    /**
     * Full-screen UI for an app: browse tree (list/grid per content style), search, now playing.
     * [onShowVideo], when set, adds a "Show video" action to its top bar.
     */
    @Composable
    fun Screen(app: MediaApp, driving: Boolean, modifier: Modifier, onShowVideo: (() -> Unit)?)

    /** Disconnects everything. */
    fun release()
}
