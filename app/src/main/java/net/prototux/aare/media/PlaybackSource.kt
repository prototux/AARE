package net.prototux.aare.media

import android.app.PendingIntent
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * Something that plays and can be controlled: our media3 connection ([MediaConnection]) or another
 * app's session seen through MediaSessionManager ([SessionTracker]). Main thread only.
 */
internal interface PlaybackSource {
    /** Stable identity, for [pickSource]. */
    val key: String
    val packageName: String
    val isConnection: Boolean
    val state: StateFlow<SourceState>

    fun playPause()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun skipTo(entry: QueueEntry)
}

/** Everything the now-playing view shows for one source. */
internal data class SourceState(
    /** Null when nothing is loaded. */
    val info: PlaybackInfo? = null,
    /** Show "pause" rather than "play": also true while buffering with play requested. */
    val showPause: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    /** The app keeps the skip buttons' slots even while they can't be used (see [transportRow]). */
    val reservePrevious: Boolean = false,
    val reserveNext: Boolean = false,
    val canSeek: Boolean = false,
    val actions: List<CustomAction> = emptyList(),
    val queue: List<QueueEntry> = emptyList(),
    val error: MediaError? = null,
)

/** An app-defined transport button (media3 media button preferences, legacy custom actions). */
internal class CustomAction(val id: String, val label: String, val icon: IconRef?, val run: () -> Unit) {
    override fun equals(other: Any?) = other is CustomAction && other.id == id && other.label == label && other.icon == icon
    override fun hashCode() = id.hashCode()
}

/** Where an icon comes from: a drawable of [packageName] (ours when null), or a URI that [packageName] supplied. */
internal data class IconRef(val packageName: String?, val resId: Int, val uri: Uri? = null)

internal data class QueueEntry(val id: Long, val title: String, val subtitle: String, val current: Boolean)

/** An error to show in a card, with the app's optional fix-it action (e.g. "Sign in"). */
internal data class MediaError(val message: String, val actionLabel: String? = null, val action: PendingIntent? = null)

internal fun SourceState.candidate(source: PlaybackSource) = SourceCandidate(
    key = source.key,
    packageName = source.packageName,
    connected = source.isConnection,
    playing = info?.playing == true,
    hasMedia = info != null,
)
