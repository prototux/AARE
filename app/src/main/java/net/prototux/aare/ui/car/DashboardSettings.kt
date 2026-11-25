package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.faces.Face

/**
 * The dashboard's cards, for the car's and the phone's settings: each can be shown or hidden, and
 * the shown ones moved up or down (the first gets the most room). [faces]: the user's own cards,
 * listed by name like the others. The calendar's box has a third state, an A: only with an event
 * soon ([calendarAuto]). Lines part the big card, the one that always shows and those that take
 * turns (with [mapBig], the map is the big one wherever it is). [rowHeight] is the touch target: the car's is bigger than the phone's.
 */
@Composable
internal fun DashboardCardsEditor(
    order: List<DashItem>,
    rowHeight: Dp,
    available: List<DashCard> = DashCard.entries,
    faces: List<Face> = emptyList(),
    mapBig: Boolean,
    calendarAuto: Boolean,
    onCalendarAuto: (Boolean) -> Unit,
    onChange: (List<DashItem>) -> Unit,
) {
    val all: List<DashItem> = available + faces.map { FaceCard(it.id) }
    // A card saved in the order may not be listed now (its file is gone, the adapter's card): it keeps its place.
    val listed = order.filter { it in all }
    Column {
        Text(stringResource(R.string.dash_cards_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dash_cards_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The shown cards in their order, then the hidden ones.
        val zones = dashboardZones(listed, mapBig)
        (listed + all.filter { it !in order }).forEach { card ->
            val i = listed.indexOf(card)
            val shown = i >= 0
            if (i > 0 && zones[card] != zones[listed[i - 1]]) HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // The last shown card can't be hidden: an empty dashboard is no use.
            val canHide = listed.size > 1
            val calendar = card == DashCard.CALENDAR
            val canToggle = !shown || canHide || calendar
            val mode = if (!shown) CalendarCardMode.OFF else if (calendarAuto) CalendarCardMode.AUTO else CalendarCardMode.ALWAYS
            Row(Modifier.fillMaxWidth().heightIn(min = rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = rowHeight)
                        .carClickable(RectangleShape, enabled = canToggle) {
                            if (!calendar) onChange(order.withCard(card, !shown))
                            else {
                                val next = mode.next(canHide)
                                if (next != CalendarCardMode.OFF) onCalendarAuto(next == CalendarCardMode.AUTO)
                                onChange(order.withCard(card, next != CalendarCardMode.OFF))
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Decorative only (onCheckedChange = null): the row above carries the click and
                    // the knob focus highlight, see the allowlist in CarScreenReachabilityCheckTest.
                    if (calendar && mode == CalendarCardMode.AUTO) AutoBox()
                    else Checkbox(checked = shown, onCheckedChange = null, enabled = canToggle)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(cardName(card, faces), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (calendar && mode == CalendarCardMode.AUTO) Text(stringResource(R.string.dash_calendar_auto),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (shown) {
                    IconButton(onClick = { onChange(order.movedAmong(listed, i, -1)) }, enabled = i > 0, modifier = Modifier.size(rowHeight)) {
                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.common_up))
                    }
                    IconButton(onClick = { onChange(order.movedAmong(listed, i, 1)) }, enabled = i < listed.lastIndex, modifier = Modifier.size(rowHeight)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.common_down))
                    }
                }
            }
        }
    }
}

/** A ticked box with an A for its tick: shown on its own when there is something to show. The size of a Checkbox's. */
@Composable
private fun AutoBox() {
    val auto = stringResource(R.string.dash_auto)
    Box(
        Modifier.padding(2.dp).size(20.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = auto },
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold,
            // The box's size, whatever the text size setting.
            fontSize = with(LocalDensity.current) { 14.dp.toSp() }, lineHeight = with(LocalDensity.current) { 16.dp.toSp() })
    }
}

/** The seconds between changes the settings offer, 0 (never) first. */
internal val ROTATE_SECONDS = listOf(0, 5, 10, 15, 30, 60)

/** [ROTATE_SECONDS] named, with the [current] one if it's another (an imported file's), for a choice in the car's and the phone's settings. */
@Composable
internal fun rotateOptions(current: Int): List<Pair<String, String>> = (ROTATE_SECONDS + current).distinct().sorted().map {
    it.toString() to if (it == 0) stringResource(R.string.dash_rotate_never) else stringResource(R.string.dash_rotate_seconds, it)
}

@Composable
private fun cardName(card: DashItem, faces: List<Face>): String = when (card) {
    is DashCard -> stringResource(card.label)
    is FaceCard -> faces.firstOrNull { it.id == card.faceId }?.name.orEmpty()
}

private val DashCard.label: Int
    get() = when (this) {
        DashCard.MAP -> R.string.dash_card_map
        DashCard.MEDIA -> R.string.dash_card_media
        DashCard.CALENDAR -> R.string.dash_card_calendar
        DashCard.NOTIFICATION -> R.string.dash_card_notification
        DashCard.WEATHER -> R.string.dash_card_weather
        DashCard.CLOCK -> R.string.dash_card_clock
        DashCard.HOME -> R.string.dash_card_home
        DashCard.CAR -> R.string.obd_dash_card
    }
