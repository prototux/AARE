package net.prototux.aare.media

import android.app.PendingIntent
import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.media3.common.MediaItem
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small helpers over media3 items and bundles. */

internal val MediaItem.titleText: String
    get() = (mediaMetadata.title ?: mediaMetadata.displayTitle ?: "").toString()

internal val MediaItem.subtitleText: String
    get() = (mediaMetadata.subtitle ?: mediaMetadata.artist ?: mediaMetadata.albumArtist ?: mediaMetadata.albumTitle
        ?: mediaMetadata.description ?: "").toString()

internal val MediaItem.isBrowsableItem: Boolean get() = mediaMetadata.isBrowsable == true

/** Items that say nothing are tried as playable: that is what a tap would most likely mean. */
internal val MediaItem.isPlayableItem: Boolean get() = mediaMetadata.isPlayable ?: !isBrowsableItem

internal val MediaItem.isExplicit: Boolean
    get() = mediaMetadata.extras?.getLong(MediaKeys.IS_EXPLICIT, 0L) == 1L

/** 0..1 when the app reports partial completion (podcasts, audiobooks). */
internal val MediaItem.completion: Float?
    get() {
        val e = mediaMetadata.extras ?: return null
        if (e.containsKey(MediaKeys.COMPLETION_PERCENTAGE)) return e.getDouble(MediaKeys.COMPLETION_PERCENTAGE).toFloat().coerceIn(0f, 1f)
        return when (e.getInt(MediaKeys.COMPLETION_STATUS, -1)) {
            1 -> 0.5f
            2 -> 1f
            else -> null
        }
    }

/** Layout facts of an item, for [sections]. */
internal fun MediaItem.entryHints() = EntryHints(
    browsable = isBrowsableItem,
    groupTitle = mediaMetadata.extras?.getString(MediaKeys.CONTENT_STYLE_GROUP_TITLE),
    singleStyle = ContentStyle.fromHint(mediaMetadata.extras?.getInt(MediaKeys.CONTENT_STYLE_SINGLE_ITEM, 0)),
)

/** The content-style hints a bundle carries (root extras, or an item's extras for its children). */
internal fun hintsOf(extras: Bundle?) = StyleHints(
    browsable = ContentStyle.fromHint(extras?.getInt(MediaKeys.CONTENT_STYLE_BROWSABLE, 0)),
    playable = ContentStyle.fromHint(extras?.getInt(MediaKeys.CONTENT_STYLE_PLAYABLE, 0)),
)

/** The app's "fix it" action (e.g. sign in) carried in error extras, if any. */
internal fun errorAction(extras: Bundle?): Pair<String?, PendingIntent?> {
    if (extras == null) return null to null
    val label = extras.getString(MediaKeys.ERROR_RESOLUTION_ACTION_LABEL)
    val intent = BundleCompat.getParcelable(extras, MediaKeys.ERROR_RESOLUTION_ACTION_INTENT, PendingIntent::class.java)
    return if (intent != null) label to intent else null to null
}

/** Thrown by [MediaConnection] browse calls with a message fit for the error card. */
internal class BrowseException(val error: MediaError) : Exception(error.message)

/** Awaits a media3 future; cancelling the coroutine cancels the future. */
internal suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: java.util.concurrent.CancellationException) {
            cont.cancel(e)
        }
    }, MoreExecutors.directExecutor())
    cont.invokeOnCancellation { cancel(false) }
}
