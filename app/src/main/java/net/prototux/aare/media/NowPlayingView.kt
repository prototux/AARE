package net.prototux.aare.media

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Now playing: big art, title/artist/album, progress (seekable when the app allows it), transport
 * buttons, the app's extra buttons and a queue button. Side by side in landscape, stacked in portrait.
 */
@Composable
internal fun NowPlayingView(hub: DefaultMediaHub, source: PlaybackSource, state: SourceState, setView: (MediaView) -> Unit) {
    val info = state.info
    if (info == null) {
        // right after a tap the app needs a moment to resolve the item: wait before saying "nothing"
        var waited by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(4_000)
            waited = true
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            state.error?.let { ErrorCard(it, Modifier.fillMaxWidth().padding(16.dp)) }
            Placeholder(if (waited) stringResource(R.string.media_nothing_playing) else stringResource(R.string.media_starting), loading = !waited && state.error == null,
                modifier = Modifier.weight(1f), icon = Icons.Rounded.MusicNote)
            if (waited) {
                Pill(stringResource(R.string.media_action_browse), selected = true, onClick = { setView(MediaView.BROWSE) }, icon = Icons.Rounded.LibraryMusic)
                Spacer(Modifier.height(32.dp))
            }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        // a soft backdrop of the art, fading into the background
        if (info.art != null) {
            Image(info.art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.22f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            val portrait = maxHeight > maxWidth
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (portrait) {
                    ArtBox(info.art, Icons.Rounded.MusicNote, Modifier.weight(1f, fill = false).sizeIn(maxWidth = 420.dp).aspectRatio(1f), RoundedCornerShape(28.dp))
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.fillMaxWidth()) { Details(state, info, source) }
                } else {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ArtBox(info.art, Icons.Rounded.MusicNote, Modifier.fillMaxHeight().aspectRatio(1f), RoundedCornerShape(28.dp))
                        Spacer(Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) { Details(state, info, source) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Controls(hub, source, state, setView)
            }
        }
    }
}

@Composable
private fun Details(state: SourceState, info: PlaybackInfo, source: PlaybackSource) {
    state.error?.let {
        ErrorCard(it, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
    }
    Text(info.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (info.artist.isNotEmpty()) {
        Text(info.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (info.album.isNotEmpty()) {
        Text(info.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(16.dp))
    Progress(info, state.canSeek, source::seekTo)
}

/**
 * The transport row across the full width: previous / play-pause / next in the middle, the app's
 * own buttons split on both sides, the queue at the end. Extra buttons are dropped when narrow.
 */
@Composable
private fun Controls(hub: DefaultMediaHub, source: PlaybackSource, state: SourceState, setView: (MediaView) -> Unit) {
    val touch = LocalCarDimens.current.touchTarget
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val hasQueue = state.queue.isNotEmpty()
        val slots = ((maxWidth - PLAY_SIZE - touch * 2) / touch).toInt() - (if (hasQueue) 1 else 0)
        val actions = state.actions.take(slots.coerceAtLeast(0))
        val left = actions.take((actions.size + 1) / 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            left.forEach { ActionButton(hub, it) }
            CircleButton(Icons.Rounded.SkipPrevious, stringResource(R.string.media_action_previous), onClick = source::previous, enabled = state.canPrevious)
            CircleButton(
                if (state.showPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (state.showPause) R.string.media_action_pause else R.string.media_action_play),
                onClick = source::playPause, filled = true, size = PLAY_SIZE,
            )
            CircleButton(Icons.Rounded.SkipNext, stringResource(R.string.media_action_next), onClick = source::next, enabled = state.canNext)
            actions.drop(left.size).forEach { ActionButton(hub, it) }
            if (hasQueue) CircleButton(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.media_queue_title), onClick = { setView(MediaView.QUEUE) })
        }
    }
}

private val PLAY_SIZE = 96.dp

/** Position extrapolated from the last sample while playing; a slider when seeking is allowed. */
@Composable
private fun Progress(info: PlaybackInfo, canSeek: Boolean, seekTo: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(info.playing, info.sampledAtMs) {
        now = SystemClock.elapsedRealtime()
        while (info.playing) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val raw = if (info.playing) info.positionMs + (now - info.sampledAtMs) else info.positionMs
    val pos = if (info.durationMs > 0) raw.coerceIn(0, info.durationMs) else raw.coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    if (info.durationMs > 0) {
        val fraction = pos.toFloat() / info.durationMs
        if (canSeek) {
            Slider(
                value = if (dragging) dragValue else fraction,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    seekTo((dragValue * info.durationMs).toLong())
                    dragging = false
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(8.dp))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val shown = if (dragging) (dragValue * info.durationMs).toLong() else pos
        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (info.durationMs > 0) {
            Text(formatTime(info.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An app-defined button: its icon (tinted like ours) or, failing that, a short label. */
@Composable
private fun ActionButton(hub: DefaultMediaHub, action: CustomAction) {
    val d = LocalCarDimens.current
    val icon = rememberIcon(hub.art, action.icon, 96)
    Box(Modifier.size(d.touchTarget).clip(CircleShape).clickable { action.run() }, contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(icon.asImageBitmap(), action.label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(d.iconSize))
        } else {
            Text(action.label.take(6), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** The play queue; tapping an entry jumps to it. */
@Composable
internal fun QueueView(source: PlaybackSource, state: SourceState, done: () -> Unit) {
    if (state.queue.isEmpty()) {
        Placeholder(stringResource(R.string.media_queue_empty), loading = false, icon = Icons.Rounded.LibraryMusic)
        return
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.queue.indexOfFirst { it.current }.coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        // keyed on the position too: buggy apps repeat queue ids, and duplicate keys crash the list
        itemsIndexed(state.queue, key = { i, entry -> "$i:${entry.id}" }) { _, entry ->
            val bg = if (entry.current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            Row(
                Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(RoundedCornerShape(20.dp)).background(bg)
                    .clickable { source.skipTo(entry); done() }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (entry.current) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.subtitle.isNotEmpty()) {
                        Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
