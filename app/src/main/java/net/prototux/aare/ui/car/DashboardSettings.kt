package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.DragHandle
import net.prototux.aare.ui.Reorder
import net.prototux.aare.ui.RowDrag

/**
 * The dashboard's cards, for the car's and the phone's settings: each can be shown or hidden, and
 * the shown ones dragged up or down by their handle (the first gets the most room). [rowHeight] is
 * the touch target: the car's is bigger than the phone's.
 */
@Composable
internal fun DashboardCardsEditor(order: List<DashCard>, rowHeight: Dp, onChange: (List<DashCard>) -> Unit) {
    val drag = remember { RowDrag() }
    val up = stringResource(R.string.common_up)
    val down = stringResource(R.string.common_down)
    Column {
        Text(stringResource(R.string.dash_cards_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dash_cards_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The shown cards in their order, then the hidden ones.
        (order + DashCard.entries.filter { it !in order }).forEach { card -> key(card) {
            val i = order.indexOf(card)
            val shown = i >= 0
            // The last shown card can't be hidden: an empty dashboard is no use.
            val canToggle = !(shown && order.size == 1)
            var height by remember { mutableIntStateOf(1) }
            val move = { by: Int -> onChange(order.moved(i, by)) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = rowHeight).onSizeChanged { height = it.height }
                    .then(
                        // The dragged card follows the finger over the others.
                        if (drag.key == card) Modifier.zIndex(1f).graphicsLayer { translationY = drag.offset; shadowElevation = 8.dp.toPx() }
                            .background(MaterialTheme.colorScheme.surface)
                        else Modifier,
                    )
                    .semantics {
                        if (shown) customActions = listOfNotNull(
                            CustomAccessibilityAction(up) { move(-1); true }.takeIf { i > 0 },
                            CustomAccessibilityAction(down) { move(1); true }.takeIf { i < order.lastIndex },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Hidden cards keep the handle's room, so the checkboxes line up.
                if (shown) DragHandle(Reorder(i, order.size, card, drag, move)) { height } else Spacer(Modifier.width(40.dp))
                Row(
                    Modifier.weight(1f).heightIn(min = rowHeight).clickable(enabled = canToggle) { onChange(order.withCard(card, !shown)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = shown, onCheckedChange = null, enabled = canToggle)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(card.label), style = MaterialTheme.typography.bodyLarge)
                }
            }
        } }
    }
}

private val DashCard.label: Int
    get() = when (this) {
        DashCard.MAP -> R.string.dash_card_map
        DashCard.MEDIA -> R.string.dash_card_media
        DashCard.CALENDAR -> R.string.dash_card_calendar
        DashCard.NOTIFICATION -> R.string.dash_card_notification
        DashCard.WEATHER -> R.string.dash_card_weather
    }
