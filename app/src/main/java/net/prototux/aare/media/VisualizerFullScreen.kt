package net.prototux.aare.media

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The visualizer on the whole car screen, in stereo: left channel on the left half, right on the
 * right, low frequencies at the outside edges. Any tap leaves; the host sends Back and the knob's
 * press to [DefaultMediaHub.exitFullScreen] and keeps its turns from reaching the screen below.
 * Closes by itself when it's no longer allowed (switched off, or the car moves without passenger
 * mode), and on pause once the bars have fallen. Shows the track for a few seconds on each new one.
 */
@Composable
internal fun VisualizerFullScreen(hub: DefaultMediaHub, driving: Boolean, modifier: Modifier) {
    val settings = hub.settings
    @Suppress("UNUSED_EXPRESSION") hub.visualSettings // recompose when they change
    val visual = NowPlayingVisuals.style(settings)
    val allowed = settings.visualizerFullScreen && !driving
    LaunchedEffect(allowed) { if (!allowed) hub.exitFullScreen() }
    if (!allowed) return
    val playback by hub.playback.collectAsState()
    Box(
        modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { hub.exitFullScreen() },
    ) {
    val art = playback?.art
    if (art != null && settings.visualizerArtBackground) {
        Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.22f)
    }
    // Edge to edge: the bars rise from the very bottom of the screen.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frames = rememberSpectrumFrames(
            bandsFor(maxWidth / 2), stereo = true, running = playback?.playing == true,
            delayMs = { settings.visualizerDelayMs }, verboseLog = settings.verboseProtocolLog, fallDbPerSec = fallDbPerSec(settings),
        )
        visual.Content(frames, stereo = true, style = visualStyle(settings), modifier = Modifier.fillMaxSize())
        // Paused: the bars fall, then back to now playing rather than a black screen.
        val paused = playback?.playing != true
        LaunchedEffect(paused) {
            if (!paused) return@LaunchedEffect
            snapshotFlow { frames.hasSound }.first { !it }
            hub.exitFullScreen()
        }
    }
    TrackFlash(playback, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
    }
}

/**
 * On opening and on each new track: the art drops in from the top edge as a circle, opens into a
 * pill with the title and artist, and a few seconds later folds back and goes up again.
 */
@Composable
private fun TrackFlash(playback: PlaybackInfo?, modifier: Modifier) {
    val key = playback?.let { it.title to it.artist }
    val unfold = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        islandShow(rise, unfold)
        delay(TRACK_FLASH_MS)
        islandHide(rise, unfold)
    }
    val info = playback ?: return
    Island(
        unfold = { unfold.value }, rise = { rise.value }, fromTop = true, modifier = modifier,
        pillHeight = FLASH_SIZE, centerSize = FLASH_SIZE, pillColor = Color.Black.copy(alpha = 0.6f),
        center = {
            if (info.art != null) {
                Image(info.art.asImageBitmap(), null, Modifier.size(FLASH_SIZE).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(FLASH_SIZE).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(FLASH_SIZE / 2))
                }
            }
        },
        end = {
            Column(Modifier.widthIn(max = 480.dp).padding(end = 12.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOf(info.artist, info.album).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}

private val FLASH_SIZE = 72.dp
private const val TRACK_FLASH_MS = 4_000L
