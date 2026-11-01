package net.prototux.aare.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Full-screen UI of one media app: a top bar (back, title or tabs, search, now playing) over the
 * browse tree, the now-playing view, the queue or the search keyboard.
 */
@Composable
internal fun MediaAppScreen(hub: DefaultMediaHub, app: MediaApp, driving: Boolean, modifier: Modifier) {
    LaunchedEffect(app.service) { hub.select(app) }
    val current by hub.connection.collectAsState()
    val connection = current?.takeIf { it.app.service == app.service }
    if (connection == null) {
        Placeholder(stringResource(R.string.media_connecting_to, app.label), loading = true, modifier = modifier.background(MaterialTheme.colorScheme.background))
        return
    }
    val ui = remember(connection) { hub.uiFor(connection) }
    val status by connection.status.collectAsState()
    val source by connection.state.collectAsState()

    LaunchedEffect(status) { (status as? MediaConnection.Status.Ready)?.let(ui::onReady) }
    // subscribe to the levels the user can see, so lists refresh when the app changes them
    val watched = ui.watched
    LaunchedEffect(connection, watched) { connection.watch(watched) }
    LaunchedEffect(ui) {
        if (!ui.opened) {
            ui.opened = true
            if (connection.state.value.info?.playing == true) ui.view = MediaView.NOW_PLAYING
        }
    }
    BackHandler(enabled = ui.canGoBack) { ui.back() }

    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        TopBar(app, ui, status, source)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = ui.view,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "media-view",
            ) { view ->
                when (view) {
                    MediaView.BROWSE -> BrowseBody(hub, ui, status, source)
                    MediaView.NOW_PLAYING -> NowPlayingView(hub, connection, source) { ui.view = it }
                    MediaView.QUEUE -> QueueView(connection, source) { ui.view = MediaView.NOW_PLAYING }
                    MediaView.SEARCH -> SearchView(ui, driving)
                }
            }
        }
    }
}

@Composable
private fun TopBar(app: MediaApp, ui: MediaUi, status: MediaConnection.Status, source: SourceState) {
    val d = LocalCarDimens.current
    Row(
        Modifier.fillMaxWidth().height(d.touchTarget + 16.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.canGoBack) {
            CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onClick = ui::back)
        } else {
            Box(Modifier.size(d.touchTarget), contentAlignment = Alignment.Center) { AppBadge(app, 48) }
        }
        Spacer(Modifier.width(12.dp))
        val tabs = ui.tabs
        Box(Modifier.weight(1f)) {
            when {
                ui.view == MediaView.NOW_PLAYING -> Title(app.label)
                ui.view == MediaView.QUEUE -> Title(stringResource(R.string.media_queue_title))
                ui.view == MediaView.SEARCH -> Title(ui.query.ifEmpty { stringResource(R.string.media_search_app, app.label) })
                ui.stack.isNotEmpty() -> Title(ui.stack.last().title)
                !tabs.isNullOrEmpty() -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tabs.forEachIndexed { i, item -> Pill(item.titleText, selected = i == ui.tab, onClick = { ui.selectTab(i) }) }
                }
                else -> Title(app.label)
            }
        }
        val ready = status as? MediaConnection.Status.Ready
        if (ready?.searchSupported == true && ui.view == MediaView.BROWSE) {
            CircleButton(Icons.Rounded.Search, stringResource(R.string.media_action_search), onClick = { ui.view = MediaView.SEARCH })
        }
        if (ui.view == MediaView.NOW_PLAYING) {
            CircleButton(Icons.Rounded.LibraryMusic, stringResource(R.string.media_action_browse), onClick = { ui.view = MediaView.BROWSE })
        } else if (ui.view == MediaView.BROWSE && source.info != null) {
            NowPlayingChip(source) { ui.view = MediaView.NOW_PLAYING }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun AppBadge(app: MediaApp, sizeDp: Int) {
    if (app.icon != null) {
        Image(app.icon.asImageBitmap(), app.label, Modifier.size(sizeDp.dp).clip(CircleShape))
    } else {
        Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Small "now playing" shortcut: the art with an equalizer badge. */
@Composable
private fun NowPlayingChip(source: SourceState, onClick: () -> Unit) {
    val info = source.info ?: return
    Row(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick).padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtBox(info.art, Icons.Rounded.MusicNote, Modifier.size(48.dp), CircleShape)
        Spacer(Modifier.width(12.dp))
        Icon(
            if (info.playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, stringResource(R.string.media_now_playing_label),
            tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp),
        )
    }
}

// ---------------------------------------------------------------- browse

@Composable
private fun BrowseBody(hub: DefaultMediaHub, ui: MediaUi, status: MediaConnection.Status, source: SourceState) {
    val level = ui.level
    Column(Modifier.fillMaxSize()) {
        val error = (status as? MediaConnection.Status.Failed)?.error ?: source.error
        if (error != null) {
            ErrorCard(
                error,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                onRetry = if (status is MediaConnection.Status.Failed) ui.connection::retry else null,
            )
        }
        when {
            level != null -> BrowseLevel(hub, ui, level)
            status is MediaConnection.Status.Connecting -> Placeholder(stringResource(R.string.media_connecting_to, ui.connection.app.label), loading = true)
            status is MediaConnection.Status.Failed -> Unit
            else -> Placeholder(stringResource(R.string.common_loading), loading = true)
        }
    }
}

/**
 * One level as a single lazy grid: grid sections take one cell per item, list rows and headers
 * span the whole line, so mixed levels (albums grid, then tracks list) scroll as one.
 */
@Composable
private fun BrowseLevel(hub: DefaultMediaHub, ui: MediaUi, level: Level) {
    val loader = level.loader
    LaunchedEffect(level) {
        if (loader.items.isEmpty() && !loader.end) loader.loadMore()
    }
    val items = loader.items
    if (items.isEmpty()) {
        when {
            loader.error != null -> ErrorCard(loader.error!!, Modifier.fillMaxWidth().padding(16.dp), onRetry = loader::retry)
            loader.loading || !loader.end -> Placeholder(stringResource(R.string.common_loading), loading = true)
            else -> Placeholder(stringResource(R.string.media_nothing_here), loading = false, icon = Icons.Rounded.Folder)
        }
        return
    }
    val sections = remember(items, level.hints) { sections(items.map { it.entryHints() }, level.hints) }
    // load the next page when the user nears the end
    LaunchedEffect(level, loader) {
        snapshotFlow { (level.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to level.grid.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last >= total - 10) loader.loadMore() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        state = level.grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            if (section.title != null) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") { SectionTitle(section.title) }
            }
            section.indices.forEach { i ->
                val item = items[i]
                if (section.style.isGrid) {
                    item(key = "${i}_${item.mediaId}", contentType = "tile") { Tile(hub, item, section.style) { ui.open(item) } }
                } else {
                    item(key = "${i}_${item.mediaId}", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                        ItemRow(hub, item, section.style) { ui.open(item) }
                    }
                }
            }
        }
        if (loader.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) { Placeholder("", loading = true, modifier = Modifier.height(96.dp)) }
        }
        // a later page failed: say so at the end, retrying only that page
        loader.error?.let { e ->
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "more-error") {
                ErrorCard(
                    e.copy(message = stringResource(R.string.media_couldnt_load_more)),
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    onRetry = loader::retryMore,
                )
            }
        }
    }
}

private fun placeholderFor(item: MediaItem) = when {
    item.isBrowsableItem -> Icons.Rounded.Album
    else -> Icons.Rounded.MusicNote
}

@Composable
private fun ItemRow(hub: DefaultMediaHub, item: MediaItem, style: ContentStyle, onClick: () -> Unit) {
    val md = item.mediaMetadata
    val art = rememberArt(hub.art, md.artworkUri, md.artworkData, hub.connection.value?.packageName, 192)
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (style.isCategory) {
            // category icons are monochrome glyphs: tint them instead of framing them like art
            val tint = MaterialTheme.colorScheme.primary
            if (art != null) Icon(art.asImageBitmap(), null, tint = tint, modifier = Modifier.size(40.dp))
            else Icon(Icons.Rounded.Folder, null, tint = tint, modifier = Modifier.size(40.dp))
        } else {
            ArtBox(art, placeholderFor(item), Modifier.size(64.dp), RoundedCornerShape(12.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isExplicit) ExplicitBadge()
                Text(item.titleText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sub = item.subtitleText
            if (sub.isNotEmpty() && !style.isCategory) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.completion?.let { done ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { done }, modifier = Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
        if (item.isBrowsableItem) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun Tile(hub: DefaultMediaHub, item: MediaItem, style: ContentStyle, onClick: () -> Unit) {
    val md = item.mediaMetadata
    val art = rememberArt(hub.art, md.artworkUri, md.artworkData, hub.connection.value?.packageName, 384)
    if (style.isCategory) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tint = MaterialTheme.colorScheme.primary
            if (art != null) Icon(art.asImageBitmap(), null, tint = tint, modifier = Modifier.size(48.dp))
            else Icon(Icons.Rounded.Folder, null, tint = tint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(item.titleText, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(bottom = 8.dp)) {
        ArtBox(art, placeholderFor(item), Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(20.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (item.isExplicit) ExplicitBadge()
            Text(item.titleText, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val sub = item.subtitleText
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ExplicitBadge() {
    Box(
        Modifier.padding(end = 8.dp).size(24.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("E", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.surface)
    }
}
