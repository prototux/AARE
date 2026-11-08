package net.prototux.aare.media

import android.content.Context
import androidx.annotation.StringRes
import androidx.media3.session.SessionError
import net.prototux.aare.R

/** What [pickSource] needs to know about one playback source. */
data class SourceCandidate(
    val key: String,
    val packageName: String,
    /** Our own media3 connection, as opposed to a session seen through MediaSessionManager. */
    val connected: Boolean,
    val playing: Boolean,
    /** Has something loaded (metadata), even if paused. */
    val hasMedia: Boolean,
)

/**
 * Chooses which source "now playing" and the transport buttons follow.
 *
 * - A playing source wins: our connection first, then the previous pick, then the system's order
 *   (MediaSessionManager lists the most recently active session first).
 * - When nothing plays, stay on the previous pick so pausing never jumps to another app.
 * - Our connection hides the phone session of the same app when it has media: same player,
 *   but media3 gives us richer state (queue, custom layout, errors).
 */
fun pickSource(candidates: List<SourceCandidate>, previousKey: String?): String? {
    val connected = candidates.firstOrNull { it.connected }
    val list = candidates.filter {
        it.connected || connected == null || !connected.hasMedia || it.packageName != connected.packageName
    }
    val previous = list.firstOrNull { it.key == previousKey }
    val playing = list.filter { it.playing }
    if (playing.isNotEmpty()) {
        return (playing.firstOrNull { it.connected } ?: playing.firstOrNull { it.key == previousKey } ?: playing.first()).key
    }
    if (previous != null && previous.hasMedia) return previous.key
    return (list.firstOrNull { it.connected && it.hasMedia } ?: list.firstOrNull { it.hasMedia })?.key
}

/**
 * A driver-friendly message for a media3 [SessionError] / `LibraryResult` code (the session codes
 * equal `PlaybackException.ERROR_CODE_*`), used when the app sent no message of its own.
 */
fun errorText(context: Context, code: Int, appLabel: String): String = context.getString(errorTextRes(code), appLabel, code)

/** The string behind [errorText]. Every one takes the same arguments: %1$s the app, %2$d the code. */
@StringRes
fun errorTextRes(code: Int): Int = when (code) {
    SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> R.string.media_error_sign_in
    SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED -> R.string.media_error_premium
    SessionError.ERROR_SESSION_CONCURRENT_STREAM_LIMIT -> R.string.media_error_stream_limit
    SessionError.ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED -> R.string.media_error_parental
    SessionError.ERROR_SESSION_NOT_AVAILABLE_IN_REGION -> R.string.media_error_region
    SessionError.ERROR_SESSION_SKIP_LIMIT_REACHED -> R.string.media_error_skip_limit
    SessionError.ERROR_SESSION_SETUP_REQUIRED -> R.string.media_error_setup
    SessionError.ERROR_SESSION_END_OF_PLAYLIST -> R.string.media_error_end_of_playlist
    SessionError.ERROR_PERMISSION_DENIED -> R.string.media_error_permission
    SessionError.ERROR_IO -> R.string.media_error_io
    SessionError.ERROR_NOT_SUPPORTED -> R.string.media_error_not_supported
    SessionError.ERROR_SESSION_DISCONNECTED -> R.string.media_error_disconnected
    else -> R.string.media_error_code
}
