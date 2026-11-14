package net.prototux.aare.ui.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R

/**
 * The dashboard's cards, for the car's and the phone's settings: each can be shown or hidden, and
 * the shown ones moved up or down (the first gets the most room). [rowHeight] is the touch target:
 * the car's is bigger than the phone's.
 */
@Composable
internal fun DashboardCardsEditor(order: List<DashCard>, rowHeight: Dp, onChange: (List<DashCard>) -> Unit) {
    Column {
        Text(stringResource(R.string.dash_cards_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dash_cards_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The shown cards in their order, then the hidden ones.
        (order + DashCard.entries.filter { it !in order }).forEach { card ->
            val i = order.indexOf(card)
            val shown = i >= 0
            // The last shown card can't be hidden: an empty dashboard is no use.
            val canToggle = !(shown && order.size == 1)
            Row(Modifier.fillMaxWidth().heightIn(min = rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = rowHeight).clickable(enabled = canToggle) { onChange(order.withCard(card, !shown)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = shown, onCheckedChange = null, enabled = canToggle)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(card.label), style = MaterialTheme.typography.bodyLarge)
                }
                if (shown) {
                    IconButton(onClick = { onChange(order.moved(i, -1)) }, enabled = i > 0, modifier = Modifier.size(rowHeight)) {
                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.common_up))
                    }
                    IconButton(onClick = { onChange(order.moved(i, 1)) }, enabled = i < order.lastIndex, modifier = Modifier.size(rowHeight)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.common_down))
                    }
                }
            }
        }
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
