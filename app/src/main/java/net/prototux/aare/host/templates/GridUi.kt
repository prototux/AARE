package net.prototux.aare.host.templates

import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Badge
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import net.prototux.aare.ui.car.carFocusHighlight

/** GridTemplate: tiles of GridItems (icons or large images), with header and floating actions. */
@Composable
internal fun GridTemplateView(t: GridTemplate) {
    @Suppress("DEPRECATION")
    val header = headerSpec(t.header, t.title, t.headerAction, t.actionStrip)
    TemplateScaffold(header, fabActions = t.actions) {
        val list = t.singleList
        when {
            t.isLoading -> LoadingBox()
            list == null || list.items.isEmpty() -> EmptyMessage(list?.noItemsMessage, "Nothing to show")
            else -> ItemGrid(list, t.itemSize, t.itemImageShape == GridTemplate.ITEM_IMAGE_SHAPE_CIRCLE, fabClearance(t.actions))
        }
    }
}

/** Tile size for GridTemplate.ITEM_SIZE_*: the grid adapts the number of columns to the width. */
internal fun gridCellWidth(itemSize: Int): Dp = when (itemSize) {
    GridTemplate.ITEM_SIZE_LARGE -> 216.dp
    GridTemplate.ITEM_SIZE_MEDIUM -> 176.dp
    else -> 150.dp
}

@Composable
internal fun ItemGrid(list: ItemList, itemSize: Int, circle: Boolean, bottomClearance: Dp = 0.dp, state: LazyGridState = rememberLazyGridState()) {
    val host = LocalTemplateHost.current
    val inCard = LocalInCard.current
    val limit = host.contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID).takeIf { it > 0 } ?: Int.MAX_VALUE
    // identity, not ItemList.equals: that finds two lists whose items differ only by a bitmap image equal
    val items = remember(System.identityHashCode(list), limit) { list.items.filterIsInstance<GridItem>().take(limit) }
    val cell = gridCellWidth(itemSize)

    val visibility = list.onItemVisibilityChangedDelegate
    if (visibility != null) {
        val lastSent = remember { arrayOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(state, visibility) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .collect { idx ->
                    if (idx.isEmpty()) return@collect
                    val range = idx.min() to idx.max() + 1
                    if (lastSent[0] != range) {
                        lastSent[0] = range
                        safeCall("onItemVisibilityChanged") { visibility.sendItemVisibilityChanged(range.first, range.second, host.done) }
                    }
                }
        }
    }

    val pad = if (inCard) 12.dp else 24.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = pad, end = pad, top = 4.dp, bottom = pad + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items) { _, item -> GridItemCell(item, circle, cell) }
    }
}

/** One grid tile: image (or spinner while loading) with badge, progress, title and text. */
@Composable
internal fun GridItemCell(item: GridItem, circle: Boolean, cell: Dp, modifier: Modifier = Modifier) {
    val host = LocalTemplateHost.current
    val scheme = MaterialTheme.colorScheme
    val delegate = item.onClickDelegate
    val large = item.imageType == GridItem.IMAGE_TYPE_LARGE
    val tile = cell - 40.dp
    val shape: Shape = if (circle) CircleShape else RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .then(if (delegate != null) Modifier.carFocusHighlight(RoundedCornerShape(24.dp)).clickable { host.click(delegate) } else Modifier)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(tile)) {
            Box(
                Modifier.fillMaxSize().clip(shape).background(if (large) scheme.surfaceContainerHigh else scheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    item.isLoading -> CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 4.dp)
                    large -> CarIconView(item.image, tile, contentScale = ContentScale.Crop, shape = shape)
                    else -> CarIconView(item.image, tile * 0.55f)
                }
            }
            item.badge?.let { GridBadge(it, Modifier.align(Alignment.TopEnd)) }
        }
        item.progressBar?.let { bar ->
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { bar.progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(width = tile, height = 6.dp).clip(RoundedCornerShape(3.dp)),
                color = carColor(bar.color, scheme.primary),
                trackColor = scheme.surfaceContainerHighest,
            )
        }
        Spacer(Modifier.height(10.dp))
        CarTextView(item.title, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 2, textAlign = TextAlign.Center)
        CarTextView(item.text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GridBadge(badge: Badge, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val icon = badge.icon
    if (icon != null) {
        val bg = carColor(badge.iconBackgroundColor, scheme.surfaceContainerHighest)
        Box(
            modifier.offset(x = 6.dp, y = (-6).dp).size(36.dp).clip(CircleShape).background(bg)
                .border(3.dp, scheme.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CarIconView(icon, 22.dp, monochromeTint = contentColorOn(bg))
        }
    } else if (badge.hasDot()) {
        Box(
            modifier.offset(x = 2.dp, y = (-2).dp).size(18.dp).clip(CircleShape)
                .background(scheme.background) // ring separating the dot from the image
                .padding(3.dp).clip(CircleShape).background(carColor(badge.dotColor, scheme.error)),
        )
    }
}
