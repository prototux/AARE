package net.prototux.aare.host.templates

import androidx.car.app.OnDoneCallback
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Chip
import androidx.car.app.model.ChipSection
import androidx.car.app.model.CondensedItem
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Item
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.Section
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.SpotlightSection
import androidx.car.app.serialization.Bundleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import net.prototux.aare.ui.car.carFocusHighlight
import kotlin.coroutines.resume
import androidx.annotation.OptIn as AndroidXOptIn

/**
 * SectionedItemTemplate (experimental in the library): best effort. Sections' items live in the
 * app behind a ListDelegate and are fetched with requestItemRange; rows, grids, chips and
 * spotlight sections are drawn with the same building blocks as the other templates.
 */
@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
internal fun SectionedItemTemplateView(t: SectionedItemTemplate) {
    TemplateScaffold(headerSpec(t.header), fabActions = t.actions) {
        if (t.isLoading) {
            LoadingBox()
            return@TemplateScaffold
        }
        val pad = if (LocalInCard.current) 12.dp else 24.dp
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = maxOf(2, ((maxWidth - pad * 2) / gridCellWidth(GridSection.ITEM_SIZE_MEDIUM)).toInt())
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = pad, end = pad, top = 4.dp, bottom = pad + fabClearance(t.actions)),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(t.sections) { i, section -> SectionView(section, columns, first = i == 0) }
            }
        }
    }
}

@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
private fun SectionView(section: Section<*>, columns: Int, first: Boolean) {
    val limit = LocalTemplateHost.current.contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST).takeIf { it > 0 } ?: Int.MAX_VALUE
    val items = rememberSectionItems(section, limit)
    Column(Modifier.fillMaxWidth()) {
        val title = section.title
        if (title != null && !title.isEmpty) SectionTitle(title, Modifier.padding(top = if (first) 4.dp else 20.dp))
        when {
            items == null -> LoadingBox(Modifier.height(96.dp))
            items.isEmpty() -> section.noItemsMessage?.let {
                CarTextView(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            section is RowSection -> RowSectionView(section, items.filterIsInstance<Row>())
            section is GridSection -> GridSectionView(section, items.filterIsInstance<GridItem>(), columns)
            section is ChipSection -> ChipSectionView(items.filterIsInstance<Chip>())
            section is SpotlightSection -> SpotlightView(section, items.filterIsInstance<CondensedItem>())
        }
    }
}

@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
private fun RowSectionView(section: RowSection, rows: List<Row>) {
    val host = LocalTemplateHost.current
    // Reset on every template the app sends (identity): one that refuses the choice resends the same index.
    var selected by remember(System.identityHashCode(section)) { mutableStateOf(section.initialSelectedIndex) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { i, row ->
            val shape = groupShape(i == 0, i == rows.lastIndex)
            if (section.isSelectionGroup) {
                RowItem(row, shape, radio = selected == i) {
                    selected = i
                    host.click(row.onClickDelegate)
                }
            } else {
                RowItem(row, shape)
            }
        }
    }
}

@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
private fun GridSectionView(section: GridSection, items: List<GridItem>, columns: Int) {
    val cell = gridCellWidth(section.itemSize)
    val circle = section.itemImageShape == GridSection.ITEM_IMAGE_SHAPE_CIRCLE
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chunk.forEach { GridItemCell(it, circle, cell, Modifier.weight(1f)) }
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
private fun ChipSectionView(chips: List<Chip>) {
    val host = LocalTemplateHost.current
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        chips.forEach { chip ->
            val style = chip.style
            val bg = when {
                style?.backgroundColor.isSet() -> carColor(style?.backgroundColor, scheme.surfaceContainerHigh)
                chip.isSelected -> scheme.secondaryContainer
                else -> scheme.surfaceContainerHigh
            }
            val fg = carColor(style?.contentColor, if (chip.isSelected) scheme.onSecondaryContainer else scheme.onSurface)
            val outline = carColor(style?.outlineColor, Color.Transparent)
            val delegate = chip.onClickDelegate
            Row(
                Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(bg)
                    .border(2.dp, outline, RoundedCornerShape(32.dp))
                    .then(if (delegate != null) Modifier.carFocusHighlight(RoundedCornerShape(32.dp)).clickable { host.click(delegate) } else Modifier)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chip.startIcon?.let {
                    CarIconView(it, 28.dp, monochromeTint = fg)
                    Spacer(Modifier.width(8.dp))
                }
                CarTextView(chip.title, style = MaterialTheme.typography.labelLarge, color = fg)
                chip.endIcon?.let {
                    Spacer(Modifier.width(8.dp))
                    CarIconView(it, 28.dp, monochromeTint = fg)
                }
            }
        }
    }
}

@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
private fun SpotlightView(section: SpotlightSection, items: List<CondensedItem>) {
    val host = LocalTemplateHost.current
    val scheme = MaterialTheme.colorScheme
    val imageClick = section.imageOnClickDelegate
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(rowBackground()).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        section.image?.let { image ->
            CarIconView(
                image,
                120.dp,
                if (imageClick != null) {
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .carFocusHighlight(RoundedCornerShape(18.dp)).clickable { host.click(imageClick) }
                } else {
                    Modifier
                },
                shape = RoundedCornerShape(18.dp),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.leadingImage?.let {
                        CarIconView(it, 40.dp, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        CarTextView(item.title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                        CarTextView(item.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Fetches up to [limit] items of a section from the app; null while loading. */
@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
private fun rememberSectionItems(section: Section<*>, limit: Int): List<Item>? {
    val delegate = section.itemsDelegate
    val state = produceState<List<Item>?>(initialValue = null, section, limit) {
        val size = delegate.size
        if (size <= 0) {
            value = emptyList()
            return@produceState
        }
        val end = minOf(size, limit) - 1
        value = suspendCancellableCoroutine { cont ->
            val callback = object : OnDoneCallback {
                override fun onSuccess(response: Bundleable?) {
                    val list = runCatching { (response?.get() as? List<*>)?.filterIsInstance<Item>() }.getOrNull()
                    if (cont.isActive) cont.resume(list.orEmpty())
                }

                override fun onFailure(response: Bundleable) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            try {
                delegate.requestItemRange(0, end, callback)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }
    return state.value
}
