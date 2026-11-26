package net.prototux.aare.media

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * The visualizer behind the dashboard's media card: mono, set up like the others.
 * Runs only while it's on screen and the music plays (leaving the dashboard stops it).
 */
@Composable
internal fun MediaCardVisual(hub: DefaultMediaHub, modifier: Modifier) {
    val settings = hub.settings
    @Suppress("UNUSED_EXPRESSION") hub.visualSettings // recompose when they change
    if (!settings.visualizerOnCard) return
    val visual = NowPlayingVisuals.style(settings)
    val playback by hub.playback.collectAsState()
    BoxWithConstraints(modifier) {
        val frames = rememberSpectrumFrames(
            bandsFor(maxWidth), stereo = false, running = playback?.playing == true && !hub.visualFullScreen,
            delayMs = { settings.visualizerDelayMs }, verboseLog = false, fallDbPerSec = fallDbPerSec(settings),
        )
        if (frames.hasSound) visual.Content(frames, stereo = false, style = visualStyle(settings), modifier = Modifier.matchParentSize())
    }
}
