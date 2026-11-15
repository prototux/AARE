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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay10
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import net.prototux.aare.ui.car.carFocusHighlight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.CircleButton
import net.prototux.aare.media.Placeholder
import net.prototux.aare.media.formatTime

/**
 * Something the player can play: a content:// or http(s) URI, with a title for the screen. A
 * Jellyfin video also has its [jellyfinId]: the player asks the server how to play it (as it is,
 * or re-encoded to the bitrate cap) and falls back to [uri].
 */
data class VideoItem(val uri: String, val title: String, val subtitle: String? = null, val jellyfinId: String? = null)

/**
 * A video on the car screen, with AARE's own player ([CarVideoPlayer]).
 *
 * Like Android Auto, the picture is for parked use only: while driving it is hidden behind a
 * notice and the sound carries on (a film becomes an audiobook). Knob: turning moves between the
 * on-screen buttons (back 10 s, play/pause, forward 10 s, the sound sync) and pressing presses the
 * one in focus. On the progress bar, turning (or tilting left and right) scrubs: 10 s a detent,
 * faster the quicker it turns; touch taps or drags it. With the controls hidden, the first turn or press only shows them, focus on
 * play/pause. The car's media keys play, pause and seek. Back closes the video ([onClose]).
 *
 * Leaving the video, by Back or for another app, keeps its position ([VideosMemory]): opening it
 * again carries on from there.
 *
 * The picture is a [SurfaceView]: the car screen is a Presentation on a virtual display, so
 * SurfaceFlinger composites the video layer into the encoder's input like any window, without the
 * extra GPU copy a TextureView makes. A secure (DRM) surface would stay black there; nothing here
 * plays DRM content.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(runtime: CarRuntime, driving: Boolean, item: VideoItem, settings: Settings, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // One player per video: it's released when the video closes (below), never reused after that.
    val player = remember(item.uri) { CarVideoPlayer.create(context) }
    var playing by remember { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    /** Bumped on every key or touch: shows the controls for a while. */
    var pokes by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var syncMs by remember { mutableIntStateOf(settings.videoSyncMs) }
    /** The knob is on the progress bar: turning scrubs. */
    var barFocused by remember { mutableStateOf(false) }
    var lastScrub by remember { mutableLongStateOf(0L) }

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
        CarVideoPlayer.syncMs = settings.videoSyncMs
        onDispose {
            // Only once it started: a video closed while its stream was still being asked for keeps its place.
            if (player.currentMediaItem != null) VideosMemory.save(item.uri, player.currentPosition, player.duration, player.playWhenReady, player.playbackState == Player.STATE_ENDED)
            player.removeListener(listener)
            player.release()
            CarPlayerAudio.setActive(false)
        }
    }
    // What to play: the URI, or for Jellyfin what the server says (the file, or a re-encode).
    LaunchedEffect(item.uri) {
        var url = item.uri
        var hls = false
        var headers: Map<String, String>? = null
        val id = item.jellyfinId
        val account = if (id != null) JellyfinAccount.load(settings) else null
        if (id != null && account != null) {
            val client = JellyfinClient(account)
            headers = client.playbackHeaders()
            withContext(Dispatchers.IO) { runCatching { client.stream(id, settings.jellyfinMaxKbps, java.util.Locale.getDefault().isO3Language) } }
                .onSuccess { s ->
                    url = s.url
                    hls = s.playSessionId != null
                    DiagLog.add("car video: Jellyfin, ${s.note}")
                    s.playSessionId?.let { session -> encodings[player] = { runCatching { client.stopEncoding(session) } } }
                }
                .onFailure { DiagLog.add("car video: Jellyfin didn't say how to play it (${it.message}), playing the file as it is") }
        }
        val media = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        if (hls) media.setMimeType(MimeTypes.APPLICATION_M3U8)
        val h = headers
        if (h != null) {
            // Jellyfin: every request (playlists, segments, the file) carries the sign-in.
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(h).setAllowCrossProtocolRedirects(true)
            player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(media.build()))
        } else player.setMediaItem(media.build())
        val resume = VideosMemory.resumeAt(item.uri)
        if (resume > 0) {
            player.seekTo(resume)
            DiagLog.add("car video: carrying on from ${formatTime(resume)}")
        }
        player.prepare()
        player.playWhenReady = VideosMemory.wasPlaying(item.uri)
    }
    // A server re-encode ends with the video.
    DisposableEffect(player) {
        onDispose { encodings.remove(player)?.let { stop -> Thread(stop, "jellyfin-stop").start() } }
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
    /** Scrubs [detents] knob steps: the quicker the turns come, the bigger each step. */
    fun scrub(detents: Int) {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastScrub
        lastScrub = now
        val step = when {
            gap < 120 -> 120_000L
            gap < 250 -> 60_000L
            gap < 450 -> 30_000L
            else -> SEEK_MS
        }
        // Long films: never less than 1% of the film a step once turning fast.
        val d = player.duration
        seek(detents * if (step > SEEK_MS && d > 0) maxOf(step, d / 100) else step)
    }
    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0L))
        position = ms.coerceAtLeast(0L)
        pokes++
    }
    fun nudgeSync(ms: Int) {
        syncMs = (syncMs + ms).coerceIn(-SYNC_MAX_MS, SYNC_MAX_MS)
        settings.videoSyncMs = syncMs
        CarVideoPlayer.syncMs = syncMs
        pokes++
    }
    fun playPause() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = !player.playWhenReady
        pokes++
    }

    // Keyed on the player: the keys must drive this video's player, not a released one.
    DisposableEffect(player) {
        val back: () -> Boolean = { onClose(); true }
        runtime.screenBack = back
        val input = object : CarRuntime.CarScreenInput {
            override fun onKey(code: Int, down: Boolean, longPress: Boolean): Boolean {
                if (code in KNOB_KEYS) {
                    // Controls showing: the knob presses and moves between the buttons (the focus).
                    if (controls || driving) {
                        pokes++
                        // On the progress bar, left and right scrub too.
                        if (barFocused && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                            if (down) scrub(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                            return true
                        }
                        return false
                    }
                    // Hidden: the first press or tilt only brings them back.
                    if (!down) pokes++
                    return true
                }
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (!down) playPause()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> if (!down && !player.playWhenReady) playPause()
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (!down && player.playWhenReady) playPause()
                    KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) seek(-SEEK_MS)
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) seek(SEEK_MS)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (!down) seek(-BIG_SEEK_MS)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> if (!down) seek(BIG_SEEK_MS)
                    else -> return false
                }
                return true
            }

            // Turning moves the focus between the buttons; with the controls hidden it shows them first.
            override fun onRotary(delta: Int): Boolean {
                val shown = controls || driving
                pokes++
                if (shown && barFocused) { scrub(delta); return true }
                return !shown
            }
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
            Controls(item, playing, position, duration, syncMs, onBack = onClose, onPlayPause = ::playPause, onSeek = ::seek, onSync = ::nudgeSync,
                onSeekTo = ::seekTo, onBarFocus = { barFocused = it })
        }
    }
}

@Composable
private fun Controls(
    item: VideoItem, playing: Boolean, position: Long, duration: Long, syncMs: Int,
    onBack: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onSync: (Int) -> Unit,
    onSeekTo: (Long) -> Unit, onBarFocus: (Boolean) -> Unit,
) {
    // The controls leaving take the focus with them.
    DisposableEffect(Unit) { onDispose { onBarFocus(false) } }
    // Each time the controls come up, the knob starts on play/pause.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
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
                SeekBar(position, duration, onSeekTo, onBarFocus, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(if (duration > 0) formatTime(duration) else "--:--", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                CircleButton(Icons.Rounded.Replay10, stringResource(R.string.video_back_10), onClick = { onSeek(-SEEK_MS) })
                Spacer(Modifier.width(24.dp))
                CircleButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.video_pause else R.string.video_play),
                    onClick = onPlayPause, filled = true, modifier = Modifier.focusRequester(playFocus))
                Spacer(Modifier.width(24.dp))
                CircleButton(Icons.Rounded.Forward10, stringResource(R.string.video_forward_10), onClick = { onSeek(SEEK_MS) })
                // Sound sync, on the right: earlier, the offset, later.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    CircleButton(Icons.Rounded.Remove, stringResource(R.string.video_sync_earlier), onClick = { onSync(-SYNC_STEP_MS) })
                    Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.video_sync_label), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        Text(if (syncMs == 0) "0 ms" else "%+d ms".format(syncMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    CircleButton(Icons.Rounded.Add, stringResource(R.string.video_sync_later), onClick = { onSync(SYNC_STEP_MS) })
                }
            }
        }
    }
}

/**
 * The progress bar, which the knob can land on (highlighted, thicker, with a thumb) to scrub; a
 * tap or a drag seeks there by touch.
 */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeekTo: (Long) -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val track = Color.White.copy(alpha = 0.3f)
    val fill = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    fun at(x: Float, width: Int) { if (duration > 0 && width > 0) onSeekTo((duration * (x / width).coerceIn(0f, 1f)).toLong()) }
    Canvas(
        modifier.height(32.dp).clip(shape).carFocusHighlight(shape)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .focusable()
            .pointerInput(duration) { detectTapGestures { at(it.x, size.width) } }
            .pointerInput(duration) { detectHorizontalDragGestures { change, _ -> at(change.position.x, size.width) } },
    ) {
        val y = size.height / 2
        val inset = 12.dp.toPx()
        val w = size.width - 2 * inset
        val stroke = (if (focused) 8.dp else 4.dp).toPx()
        drawLine(track, Offset(inset, y), Offset(inset + w, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(fill, Offset(inset, y), Offset(inset + w * fraction, y), strokeWidth = stroke, cap = StrokeCap.Round)
        if (focused) drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(inset + w * fraction, y))
    }
}

private const val SEEK_MS = 10_000L
/** The server re-encodes running for a player, to end when it closes (main thread). */
private val encodings = HashMap<Player, () -> Unit>()
private const val BIG_SEEK_MS = 60_000L
private const val CONTROLS_MS = 4_000L
private const val SYNC_STEP_MS = 50
private const val SYNC_MAX_MS = 2_000

/** The knob's press and tilt (and a D-pad's keys): they work the on-screen buttons. */
private val KNOB_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
)
