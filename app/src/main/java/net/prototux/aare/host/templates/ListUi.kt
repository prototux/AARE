package net.prototux.aare.host.templates

import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.CarText
import androidx.car.app.model.Item
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Toggle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import net.prototux.aare.R
import net.prototux.aare.ui.car.carFocusHighlight
import net.prototux.aare.ui.theme.LocalCarDimens

/** ListTemplate: a single list or titled sections, with header, action strip and floating actions. */
@Composable
internal fun ListTemplateView(t: ListTemplate) {
    @Suppress("DEPRECATION")
    val header = headerSpec(t.header, t.title, t.headerAction, t.actionStrip)
    TemplateScaffold(header, fabActions = t.actions) {
        if (t.isLoading) {
            LoadingBox()
            return@TemplateScaffold
        }
        val single = t.singleList
        val sections = if (single != null) listOf(ListSection(null, single)) else t.sectionedLists.map { ListSection(it.header, it.itemList) }
        ItemListView(sections, ConstraintManager.CONTENT_LIMIT_TYPE_LIST, bottomClearance = fabClearance(t.actions))
    }
}

/** One titled group of rows: a SectionedItemList, or a single list (no title). */
internal data class ListSection(val header: CarText?, val list: ItemList)

/** What a LazyColumn line is: a section title, a row, or a section's "no items" message. */
private sealed interface Line {
    val section: Int

    class Title(override val section: Int, val text: CarText) : Line
    class Entry(override val section: Int, val index: Int, val item: Item, val first: Boolean, val last: Boolean) : Line
    class Empty(override val section: Int, val text: CarText?) : Line
}

/**
 * Scrollable rows of one or more ItemLists. Truncates to the host's content limit, renders radio
 * selection lists (OnSelectedDelegate) and reports the visible range of each list to its
 * OnItemVisibilityChangedDelegate (apps use it to lazily load or to drive map markers).
 */
@Composable
internal fun ItemListView(
    sections: List<ListSection>,
    limitType: Int,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
    state: LazyListState = rememberLazyListState(),
) {
    val host = LocalTemplateHost.current
    val inCard = LocalInCard.current
    val limit = host.contentLimit(limitType).takeIf { it > 0 } ?: Int.MAX_VALUE
    // Keyed on identity, like the selections below: Row.equals compares images with CarIcon.equals, which finds all
    // bitmaps equal, so an equal-looking refresh must still bring its new rows (images, click delegates).
    val listIds = sections.map { System.identityHashCode(it.list) }
    val lines = remember(listIds, limit) { buildLines(sections, limit) }

    if (lines.none { it is Line.Entry }) {
        val message = sections.firstNotNullOfOrNull { it.list.noItemsMessage }
        EmptyMessage(message, stringResource(R.string.common_nothing_to_show), modifier)
        return
    }

    // optimistic radio selection per section, reset on every template the app sends (also one that refuses the
    // change and keeps its selection)
    val selections = remember(listIds) { mutableStateMapOf<Int, Int>() }

    ReportVisibleItems(state, lines, sections)

    val pad = if (inCard) 12.dp else 24.dp
    LazyColumn(
        modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = pad, end = pad, top = if (inCard) 0.dp else 4.dp, bottom = pad + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        itemsIndexed(lines, contentType = { _, l -> l::class }) { _, line ->
            when (line) {
                is Line.Title -> SectionTitle(line.text, Modifier.padding(top = if (line.section == 0) 4.dp else 20.dp))
                is Line.Empty -> EmptyRow(line.text)
                is Line.Entry -> {
                    val list = sections[line.section].list
                    val selectDelegate = list.onSelectedDelegate
                    val row = line.item as? Row
                    if (row == null) {
                        // ListTemplate only allows rows and, from messaging apps, conversations
                        (line.item as? ConversationItem)?.let { ConversationRow(it, groupShape(line.first, line.last)) }
                        return@itemsIndexed
                    }
                    val shape = groupShape(line.first, line.last)
                    if (selectDelegate != null) {
                        val selected = (selections[line.section] ?: list.selectedIndex) == line.index
                        RowItem(row, shape, radio = selected) {
                            selections[line.section] = line.index
                            safeCall("onSelected") { selectDelegate.sendSelected(line.index, host.done) }
                        }
                    } else {
                        RowItem(row, shape)
                    }
                }
            }
        }
    }
}

private fun buildLines(sections: List<ListSection>, limit: Int): List<Line> {
    val out = ArrayList<Line>()
    var budget = limit
    sections.forEachIndexed { s, section ->
        if (budget <= 0) return@forEachIndexed
        val header = section.header
        if (header != null && !header.isEmpty) out += Line.Title(s, header)
        val items = section.list.items.take(budget)
        budget -= items.size
        if (items.isEmpty()) {
            if (sections.size > 1) out += Line.Empty(s, section.list.noItemsMessage)
        } else {
            items.forEachIndexed { i, item -> out += Line.Entry(s, i, item, i == 0, i == items.lastIndex) }
        }
    }
    return out
}

/** Sends (start, endExclusive) of the visible rows of every list that asked for it, deduplicated. */
@Composable
private fun ReportVisibleItems(state: LazyListState, lines: List<Line>, sections: List<ListSection>) {
    if (sections.none { it.list.onItemVisibilityChangedDelegate != null }) return
    val host = LocalTemplateHost.current
    val currentLines by rememberUpdatedState(lines)
    val currentSections by rememberUpdatedState(sections)
    // survives template refreshes: re-sending the same range would make some apps loop
    val lastSent = remember { HashMap<Int, Pair<Int, Int>>() }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { visible ->
                val ranges = HashMap<Int, Pair<Int, Int>>()
                for (i in visible) {
                    val line = currentLines.getOrNull(i) as? Line.Entry ?: continue
                    val r = ranges[line.section]
                    ranges[line.section] = if (r == null) line.index to line.index + 1 else minOf(r.first, line.index) to maxOf(r.second, line.index + 1)
                }
                for ((section, range) in ranges) {
                    val delegate = currentSections.getOrNull(section)?.list?.onItemVisibilityChangedDelegate ?: continue
                    if (lastSent[section] == range) continue
                    lastSent[section] = range
                    safeCall("onItemVisibilityChanged") { delegate.sendItemVisibilityChanged(range.first, range.second, host.done) }
                }
            }
    }
}

@Composable
internal fun SectionTitle(text: CarText, modifier: Modifier = Modifier) {
    CarTextView(
        text,
        modifier.padding(start = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EmptyRow(text: CarText?) {
    val shape = groupShape(true, true)
    Box(
        Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(shape).background(rowBackground())
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text != null) {
            CarTextView(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.common_no_items), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Grouped-list shape: big outer corners, small inner ones, 3 dp gaps between rows. */
@Composable
internal fun groupShape(first: Boolean, last: Boolean): Shape {
    val big = 24.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (first) big else small,
        topEnd = if (first) big else small,
        bottomStart = if (last) big else small,
        bottomEnd = if (last) big else small,
    )
}

@Composable
internal fun rowBackground() =
    if (LocalInCard.current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer

/**
 * One Row: image or place marker, title and up to two text lines, then its end decorations
 * (actions, numeric decoration, end image, toggle, radio, browse chevron).
 *
 * [radio] non-null draws a radio button and makes the whole row select it via [onSelect].
 */
@Composable
internal fun RowItem(row: Row, shape: Shape, radio: Boolean? = null, onSelect: (() -> Unit)? = null) {
    val host = LocalTemplateHost.current
    val d = LocalCarDimens.current
    val scheme = MaterialTheme.colorScheme
    val toggle = row.toggle
    // Reset on every template the app sends (identity): one that refuses the change resends the old value.
    var checked by remember(System.identityHashCode(toggle)) { mutableStateOf(toggle?.isChecked ?: false) }
    val setChecked: (Boolean) -> Unit = { value ->
        checked = value
        toggle?.onCheckedChangeDelegate?.let { delegate ->
            safeCall("onCheckedChange") { delegate.sendCheckedChange(value, host.done) }
        }
    }
    val clickDelegate = row.onClickDelegate
    val onClick: (() -> Unit)? = when {
        onSelect != null -> onSelect
        toggle != null && clickDelegate == null && toggle.isEnabled -> { { setChecked(!checked) } }
        clickDelegate != null -> { { host.click(clickDelegate) } }
        else -> null
    }
    val enabled = row.isEnabled
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = d.touchTarget).clip(shape).background(rowBackground())
            .then(if (onClick != null && enabled) Modifier.carFocusHighlight(shape).clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(start = if (radio != null) 8.dp else 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (radio != null) {
            RadioButton(selected = radio, onClick = null, modifier = Modifier.scale(1.3f).padding(horizontal = 12.dp))
            Spacer(Modifier.width(4.dp))
        }
        val image = row.image
        val marker = row.metadata?.place?.marker
        if (image != null) {
            RowImage(row, image)
            Spacer(Modifier.width(16.dp))
        } else if (marker != null) {
            PlaceMarkerBadge(marker)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            CarTextView(row.title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 2)
            val texts = row.texts
            texts.forEach { text ->
                CarTextView(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (texts.size == 1) 2 else 1,
                )
            }
            row.progressBar?.let { bar ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { bar.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = carColor(bar.color, scheme.primary),
                    trackColor = scheme.surfaceContainerHighest,
                )
            }
        }
        row.actions.forEach { action ->
            ActionView(action, ActionLook.Header)
        }
        if (row.numericDecoration != Row.NO_DECORATION && row.numericDecoration >= 0) {
            Box(
                Modifier.padding(horizontal = 8.dp).heightIn(min = 36.dp).widthIn(min = 36.dp).clip(MaterialTheme.shapes.small)
                    .background(scheme.primaryContainer).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.numericDecoration.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
            }
        }
        row.endImage?.let { end ->
            CarIconView(end, imageSize(row.rowEndImageType), Modifier.padding(horizontal = 8.dp), shape = MaterialTheme.shapes.small)
        }
        if (toggle != null) {
            ToggleSwitch(toggle, checked, setChecked)
        }
        if (row.isBrowsable) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/**
 * A messaging app's ConversationItem: avatar, title, the newest message (only how many there are while driving, as
 * in AARE's own messaging screen) and the app's actions. Parked, a tap shows the last messages and marks them read.
 */
@Composable
private fun ConversationRow(c: ConversationItem, shape: Shape) {
    val host = LocalTemplateHost.current
    val scheme = MaterialTheme.colorScheme
    val messages = c.messages
    var open by remember(c.id) { mutableStateOf(false) }
    val onClick: (() -> Unit)? = if (host.driving || messages.isEmpty()) null else { {
        open = !open
        if (open) safeCall("onMarkAsRead") { c.conversationCallbackDelegate.sendMarkAsRead(host.done) }
    } }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(shape).background(rowBackground())
            .then(if (onClick != null) Modifier.carFocusHighlight(shape).clickable(onClick = onClick) else Modifier)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        c.icon?.let {
            CarIconView(it, imageSize(Row.IMAGE_TYPE_SMALL), shape = CircleShape, contentScale = ContentScale.Crop)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            CarTextView(c.title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            when {
                host.driving -> Text(
                    if (messages.size == 1) stringResource(R.string.msg_message_count_one) else stringResource(R.string.msg_message_count_many, messages.size),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                open -> messages.takeLast(5).forEach { ConversationMessage(c, it, maxLines = 4) }
                else -> messages.lastOrNull()?.let { ConversationMessage(c, it, maxLines = 1) }
            }
        }
        c.actions.forEach { action ->
            ActionView(action, ActionLook.Header)
        }
    }
}

@Composable
private fun ConversationMessage(c: ConversationItem, m: CarMessage, maxLines: Int) {
    // Who wrote it where the title doesn't say: someone in a group, or the phone's owner.
    val sender = m.sender?.name?.toString()?.takeIf { it.isNotBlank() && it != c.title.plain() }
    if (sender != null) Text(sender, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    CarTextView(m.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines)
}

@Composable
private fun ToggleSwitch(toggle: Toggle, checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = toggle.isEnabled,
        // the default switch is phone-sized: scale it to car targets
        modifier = Modifier.padding(horizontal = 14.dp).scale(1.25f),
    )
}

@Composable
private fun RowImage(row: Row, image: androidx.car.app.model.CarIcon) {
    val type = row.rowImageType
    val size = imageSize(type)
    val photo = type == Row.IMAGE_TYPE_LARGE || type == Row.IMAGE_TYPE_MEDIUM || type == Row.IMAGE_TYPE_SMALL
    CarIconView(
        image,
        size,
        shape = if (photo) RoundedCornerShape(if (size >= 64.dp) 14.dp else 10.dp) else null,
        contentScale = if (photo) ContentScale.Crop else ContentScale.Fit,
    )
}

internal fun imageSize(type: Int): Dp = when (type) {
    Row.IMAGE_TYPE_EXTRA_SMALL -> 28.dp
    Row.IMAGE_TYPE_ICON -> 40.dp
    Row.IMAGE_TYPE_MEDIUM -> 64.dp
    Row.IMAGE_TYPE_LARGE -> 88.dp
    else -> 52.dp // SMALL
}

/** A place list's marker ("A", "1", an icon) shown where the row image would be. */
@Composable
internal fun PlaceMarkerBadge(marker: PlaceMarker, size: Dp = 48.dp) {
    val scheme = MaterialTheme.colorScheme
    val bg = carColor(marker.color, scheme.primary)
    val fg = if (marker.color.isSet()) contentColorOn(bg) else scheme.onPrimary
    val icon = marker.icon
    if (icon != null && marker.iconType == PlaceMarker.TYPE_IMAGE) {
        CarIconView(icon, size, shape = CircleShape, contentScale = ContentScale.Crop)
        return
    }
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when {
            icon != null -> CarIconView(icon, size * 0.6f, monochromeTint = fg)
            marker.label != null -> CarTextView(marker.label, style = MaterialTheme.typography.labelLarge, color = fg)
            else -> Box(Modifier.size(size * 0.3f).clip(CircleShape).background(fg))
        }
    }
}
