package net.prototux.aare.ui.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.media.AppBadge
import net.prototux.aare.media.MediaApp

/** What a media app's screen shows: AARE's player, the app's own screen (its video), or the cover art. */
internal enum class MediaVideoView { PLAYER, VIDEO, ART }

/** The video once opened while parked; its cover art as soon as driving starts, as for other parked-only content. */
internal fun mediaVideoView(open: Boolean, driving: Boolean): MediaVideoView = when {
    !open -> MediaVideoView.PLAYER
    driving -> MediaVideoView.ART
    else -> MediaVideoView.VIDEO
}

/** "Show video" is offered when switched on, with a launcher (root or Shizuku) to run the app, and parked. */
internal fun mediaVideoOffered(enabled: Boolean, launcherAvailable: Boolean, driving: Boolean): Boolean =
    enabled && launcherAvailable && !driving

/**
 * A media app's car screen with, while parked, its video: "Show video" launches the app itself on
 * the car screen through [StandaloneAppScreen] (root or Shizuku), windowed above AARE's controls or
 * full screen. Back returns to the player. While driving the app keeps running for its sound, but
 * its screen gives way to the cover art; parking again brings the video back.
 */
@Composable
internal fun MediaVideoScreen(runtime: CarRuntime, app: MediaApp, driving: Boolean, modifier: Modifier) {
    val settings = runtime.settings
    val enabled = settings.standaloneApps && settings.mediaVideo
    val launcherAvailable = remember { AppLauncher.available(runtime.context) }
    var open by remember(app.packageName) { mutableStateOf(false) }
    var fullScreen by remember(app.packageName) { mutableStateOf(false) }
    val view = mediaVideoView(open && enabled, driving)
    if (view == MediaVideoView.PLAYER) {
        val show = if (mediaVideoOffered(enabled, launcherAvailable, driving)) ({ open = true; fullScreen = false }) else null
        runtime.media.Screen(app, driving, modifier, show)
        return
    }
    val np = runtime.ui.nowPlaying?.takeIf { it.appId == CarRuntime.mediaId(app.packageName) }
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Stays composed while driving, so the app (and its sound) keeps running under the art.
            StandaloneAppScreen(runtime, driving, app.packageName, Modifier.fillMaxSize(), onBack = { open = false; true })
            if (view == MediaVideoView.ART) {
                CoverArt(np, app, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            } else if (fullScreen) {
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
                    RoundIcon(Icons.Rounded.FullscreenExit, stringResource(R.string.media_video_exit_full_screen), filled = false) { fullScreen = false }
                }
            }
        }
        if (view == MediaVideoView.ART || !fullScreen) {
            ControlBar(np, app, runtime.actions, video = view == MediaVideoView.VIDEO,
                onFullScreen = { fullScreen = true }, onClose = { open = false })
        }
    }
}

@Composable
private fun CoverArt(np: NowPlaying?, app: MediaApp, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (np?.art != null) {
            Image(np.art.asImageBitmap(), null, Modifier.size(220.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
        } else {
            AppBadge(app, 120)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.media_video_paused_driving), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** What's playing and AARE's own transport controls, under the windowed video or the cover art. */
@Composable
private fun ControlBar(np: NowPlaying?, app: MediaApp, actions: CarActions, video: Boolean, onFullScreen: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBadge(app, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(np?.title ?: app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            np?.subtitle?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RoundIcon(Icons.Rounded.SkipPrevious, stringResource(R.string.media_action_previous), filled = false) { actions.mediaPrevious() }
        RoundIcon(if (np?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            stringResource(R.string.media_action_play_pause), filled = true) { actions.mediaPlayPause() }
        RoundIcon(Icons.Rounded.SkipNext, stringResource(R.string.media_action_next), filled = false) { actions.mediaNext() }
        if (video) RoundIcon(Icons.Rounded.Fullscreen, stringResource(R.string.media_video_full_screen), filled = false, onClick = onFullScreen)
        RoundIcon(Icons.Rounded.Close, stringResource(R.string.media_video_close), filled = false, onClick = onClose)
    }
}
