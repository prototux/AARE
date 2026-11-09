package net.prototux.aare.player

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.CircleButton
import net.prototux.aare.media.Placeholder
import net.prototux.aare.media.formatTime
import net.prototux.aare.ui.car.carFocusHighlight
import net.prototux.aare.ui.theme.LocalCarDimens

/** Where the Videos app is: a page of a list, or a video playing. */
sealed interface VideoPage {
    data object Home : VideoPage
    data object Recent : VideoPage
    data class Folder(val folder: VideoFolder) : VideoPage
    data class Playing(val item: VideoItem) : VideoPage
}

/** One row of a Videos list. [thumb] loads its picture, off the main thread. */
data class VideoRow(
    val key: String,
    val title: String,
    val subtitle: String?,
    val icon: ImageVector,
    val thumb: (suspend () -> Bitmap?)? = null,
    val open: VideoPage,
)

/**
 * The car screen's Videos app: the phone's own videos (by folder, or the newest first) and, once
 * set up on the phone, a Jellyfin server; a video plays in [VideoPlayerScreen]. Lists are for
 * parked use, like the picture: while driving a playing video keeps its sound.
 */
@Composable
internal fun VideosScreen(runtime: CarRuntime, driving: Boolean, settings: Settings, modifier: Modifier) {
    var stack by remember { mutableStateOf(listOf<VideoPage>(VideoPage.Home)) }
    val page = stack.last()
    fun open(p: VideoPage) { stack = stack + p }
    fun up() { if (stack.size > 1) stack = stack.dropLast(1) }

    if (page is VideoPage.Playing) {
        VideoPlayerScreen(runtime, driving, page.item, onClose = ::up, modifier = modifier)
        return
    }
    DisposableEffect(page) {
        val back: () -> Boolean = { if (stack.size > 1) { up(); true } else false }
        runtime.screenBack = back
        onDispose { if (runtime.screenBack === back) runtime.screenBack = null }
    }
    val context = LocalContext.current
    val title = when (page) {
        VideoPage.Home -> stringResource(R.string.videos_app_label)
        VideoPage.Recent -> stringResource(R.string.videos_recent)
        is VideoPage.Folder -> page.folder.name
        is VideoPage.Playing -> page.item.title
    }
    val rows by produceState<Result<List<VideoRow>>?>(null, page) {
        value = withContext(Dispatchers.IO) { runCatching { rowsFor(context, page) } }
    }
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(LocalCarDimens.current.touchTarget + 16.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stack.size > 1) CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onClick = ::up)
            else Box(Modifier.size(LocalCarDimens.current.touchTarget), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val r = rows
            when {
                driving -> Placeholder(stringResource(R.string.videos_parked_only), loading = false, icon = Icons.Rounded.Movie)
                r == null -> Placeholder(stringResource(R.string.common_loading), loading = true)
                r.isFailure -> Placeholder(stringResource(R.string.videos_load_failed, r.exceptionOrNull()?.message ?: ""), loading = false, icon = Icons.Rounded.Movie)
                r.getOrThrow().isEmpty() -> Placeholder(emptyText(context, page), loading = false, icon = Icons.Rounded.VideoLibrary)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(r.getOrThrow(), key = { it.key }) { row -> RowView(row) { open(row.open) } }
                }
            }
        }
    }
}

private fun emptyText(context: android.content.Context, page: VideoPage): String = when {
    page == VideoPage.Home && !LocalVideos.granted(context) -> context.getString(R.string.videos_no_permission)
    else -> context.getString(R.string.videos_none)
}

/** The rows of a list page (IO thread). */
private fun rowsFor(context: android.content.Context, page: VideoPage): List<VideoRow> = when (page) {
    VideoPage.Home -> buildList {
        val videos = LocalVideos.all(context)
        if (videos.isNotEmpty()) {
            add(VideoRow("recent", context.getString(R.string.videos_recent), context.resources.getQuantityString(R.plurals.videos_count, videos.size, videos.size),
                Icons.Rounded.History, open = VideoPage.Recent))
            LocalVideos.folders(videos).forEach { f ->
                add(VideoRow("folder:${f.id}", f.name.ifBlank { context.getString(R.string.videos_unnamed_folder) },
                    context.resources.getQuantityString(R.plurals.videos_count, f.count, f.count), Icons.Rounded.Folder, open = VideoPage.Folder(f)))
            }
        }
    }
    VideoPage.Recent -> LocalVideos.all(context).take(RECENT).map { localRow(context, it, showFolder = true) }
    is VideoPage.Folder -> LocalVideos.all(context).filter { it.folderId == page.folder.id }.map { localRow(context, it, showFolder = false) }
    is VideoPage.Playing -> emptyList()
}

private fun localRow(context: android.content.Context, v: LocalVideo, showFolder: Boolean): VideoRow {
    val parts = listOfNotNull(
        v.durationMs.takeIf { it > 0 }?.let(::formatTime),
        v.folder.takeIf { showFolder && it.isNotBlank() },
        DateUtils.getRelativeTimeSpanString(v.addedSec * 1000, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString(),
    )
    return VideoRow("local:${v.id}", v.title, parts.joinToString(" · "), Icons.Rounded.Movie,
        thumb = { LocalVideos.thumbnail(context, v, THUMB_W, THUMB_H) },
        open = VideoPage.Playing(VideoItem(v.uri.toString(), v.title, v.folder.takeIf { it.isNotBlank() })))
}

@Composable
private fun RowView(row: VideoRow, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(shape)
            .carFocusHighlight(shape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb by produceState<Bitmap?>(null, row.key) { value = row.thumb?.let { load -> withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } } }
        Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(row.icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val RECENT = 50
private const val THUMB_W = 320
private const val THUMB_H = 180
