package net.prototux.aare.player

import android.view.KeyEvent
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.CircleButton
import net.prototux.aare.media.Placeholder
import net.prototux.aare.media.formatTime

/** Something the player can play: a content:// or http(s) URI, with a title for the screen. */
data class VideoItem(val uri: String, val title: String, val subtitle: String? = null)

/**
 * A video on the car screen, with AARE's own player ([CarVideoPlayer]).
 *
 * Like Android Auto, the picture is for parked use only: while driving it is hidden behind a
 * notice and the sound carries on (a film becomes an audiobook). Knob and keys: pressing (or the
 * car's play/pause) plays or pauses, turning seeks by 10 s per detent, left and right seek too,
 * up and down show the controls; Back closes the video ([onClose]).
 *
 * The picture is a [SurfaceView]: the car screen is a Presentation on a virtual display, so
 * SurfaceFlinger composites the video layer into the encoder's input like any window, without the
 * extra GPU copy a TextureView makes. A secure (DRM) surface would stay black there; nothing here
 * plays DRM content.
 */
@Composable
fun VideoPlayerScreen(runtime: CarRuntime, driving: Boolean, item: VideoItem, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember { CarVideoPlayer.create(context) }
    var playing by remember { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    /** Bumped on every key or touch: shows the controls for a while. */
    var pokes by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }

    DisposableEffect(item.uri) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) duration = player.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) controls = true
            }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
        }
        player.addListener(listener)
        CarPlayerAudio.setActive(true)
        DiagLog.add("car video: playing ${item.title}")
        player.setMediaItem(MediaItem.Builder().setUri(item.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build()).build())
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
            CarPlayerAudio.setActive(false)
        }
    }
    // On the phone's speaker only when no car takes the sound.
    LaunchedEffect(player) { CarPlayerAudio.toCar.collect { player.volume = if (it) 0f else 1f } }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0) duration = player.duration
            delay(500)
        }
    }
    // Controls fade a few seconds after the last key, unless paused.
    LaunchedEffect(pokes, playing) {
        controls = true
        if (!playing) return@LaunchedEffect
        delay(CONTROLS_MS)
        controls = false
    }

    fun seek(ms: Long) {
        val d = player.duration
        val to = (player.currentPosition + ms).coerceAtLeast(0L).let { if (d > 0) it.coerceAtMost(d) else it }
        player.seekTo(to)
        position = to
        pokes++
    }
    fun playPause() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = !player.playWhenReady
        pokes++
    }

    DisposableEffect(Unit) {
        val back: () -> Boolean = { onClose(); true }
        runtime.screenBack = back
        val input = object : CarRuntime.CarScreenInput {
            override fun onKey(code: Int, down: Boolean, longPress: Boolean): Boolean {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (!down) playPause()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> if (!down && !player.playWhenReady) playPause()
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (!down && player.playWhenReady) playPause()
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) seek(-SEEK_MS)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) seek(SEEK_MS)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (!down) seek(-BIG_SEEK_MS)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> if (!down) seek(BIG_SEEK_MS)
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) pokes++
                    else -> return false
                }
                return true
            }

            override fun onRotary(delta: Int): Boolean { seek(delta * SEEK_MS); return true }
        }
        runtime.screenInput = input
        onDispose {
            if (runtime.screenBack === back) runtime.screenBack = null
            if (runtime.screenInput === input) runtime.screenInput = null
        }
    }

    Box(modifier.background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { pokes++ }) {
        when {
            error != null -> Placeholder(stringResource(R.string.video_error, error!!), loading = false, icon = Icons.Rounded.Movie)
            driving -> Placeholder(stringResource(R.string.video_paused_driving), loading = false, icon = Icons.Rounded.Movie)
            else -> {
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
                    onRelease = { player.clearVideoSurfaceView(it) },
                    modifier = Modifier.align(Alignment.Center).aspectRatio(ratio),
                )
                if (buffering) Placeholder("", loading = true)
            }
        }
        AnimatedVisibility(controls || driving, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(item, playing, position, duration, onBack = onClose, onPlayPause = ::playPause, onSeek = ::seek)
        }
    }
}

@Composable
private fun Controls(item: VideoItem, playing: Boolean, position: Long, duration: Long, onBack: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(if (duration > 0) formatTime(duration) else "--:--", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Icons.Rounded.Replay10, stringResource(R.string.video_back_10), onClick = { onSeek(-SEEK_MS) })
                Spacer(Modifier.width(24.dp))
                CircleButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.video_pause else R.string.video_play), onClick = onPlayPause, filled = true)
                Spacer(Modifier.width(24.dp))
                CircleButton(Icons.Rounded.Forward10, stringResource(R.string.video_forward_10), onClick = { onSeek(SEEK_MS) })
            }
        }
    }
}

private const val SEEK_MS = 10_000L
private const val BIG_SEEK_MS = 60_000L
private const val CONTROLS_MS = 4_000L
