package net.prototux.aare.ui.car

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.car.AgendaDay
import net.prototux.aare.car.AgendaItem
import net.prototux.aare.car.NextEvent
import net.prototux.aare.car.Weather
import net.prototux.aare.car.labelAgenda
import net.prototux.aare.ui.theme.LocalCarDimens
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** WMO weather codes (Open-Meteo) to an icon. */
internal fun weatherIcon(w: Weather): ImageVector = when (w.code) {
    0, 1 -> if (w.day) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    in 51..67, in 80..82 -> Icons.Rounded.Umbrella
    in 71..77, 85, 86 -> Icons.Rounded.AcUnit
    in 95..99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

/** The temperature and sky, next to the clock like Android Auto's. */
@Composable
internal fun WeatherChip(weather: Weather?) {
    val w = weather ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(weatherIcon(w), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Text("${w.temperature}${w.unit}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The weather as a dashboard card: the sky and the temperature, big. */
@Composable
internal fun WeatherCard(weather: Weather) {
    Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(weatherIcon(weather), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.width(16.dp))
        Text("${weather.temperature}${weather.unit}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The calendar card: one event looks as it always has ([CalendarCard], with its day when it isn't
 * today); more than one (Settings.calendarAgendaCount > 1) becomes an agenda, each row labelled
 * with its day ("today", "tomorrow", or the weekday) and time, still with a shortcut to navigate
 * where it has a place. Only the rows the card's height holds are shown.
 */
@Composable
internal fun CalendarAgendaCard(events: List<NextEvent>, onNavigate: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val items = remember(events) { labelAgenda(events, System.currentTimeMillis(), TimeZone.getDefault()) }
    val rowHeight = LocalCarDimens.current.touchTarget
    BoxWithConstraints(modifier) {
        // As many rows as the card's height holds (each the navigate button's touch target tall),
        // never a clipped one; room for one only, or one event: the single-event card.
        val rows = agendaRowsThatFit(maxHeight, rowHeight, padding = AGENDA_PADDING, spacing = AGENDA_SPACING)
        val first = items.first()
        if (items.size == 1 || rows <= 1) {
            // A later day's event says which day; today's keeps the card's usual time (or "Today").
            CalendarCard(first.event, onNavigate, Modifier.fillMaxSize(), whenLabel = agendaWhen(first, context).takeIf { first.day != AgendaDay.Today })
        } else {
            AgendaRows(items.take(rows), rowHeight, context, onNavigate)
        }
    }
}

private val AGENDA_PADDING = 16.dp
private val AGENDA_SPACING = 8.dp

/** How many agenda rows of [rowHeight] fit in [height], inside [padding] (top and bottom) and [spacing] apart. */
internal fun agendaRowsThatFit(height: Dp, rowHeight: Dp, padding: Dp, spacing: Dp): Int =
    ((height - padding * 2 + spacing) / (rowHeight + spacing)).toInt().coerceAtLeast(0)

@Composable
private fun AgendaRows(items: List<AgendaItem>, rowHeight: Dp, context: Context, onNavigate: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = AGENDA_PADDING), verticalArrangement = Arrangement.spacedBy(AGENDA_SPACING)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(agendaWhen(item, context), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(item.event.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.event.location.isNotBlank()) {
                    RoundIcon(Icons.Rounded.Navigation, stringResource(R.string.dash_event_navigate), filled = false) { onNavigate(item.event.location) }
                }
            }
        }
    }
}

/** "Today", "Tomorrow", or the weekday: the day of an event. */
internal fun agendaDay(item: AgendaItem, context: Context): String =
    when (val d = item.day) {
        AgendaDay.Today -> context.getString(R.string.dash_event_today)
        AgendaDay.Tomorrow -> context.getString(R.string.dash_agenda_tomorrow)
        is AgendaDay.Later -> Calendar.getInstance(Locale.getDefault()).apply { set(Calendar.DAY_OF_WEEK, d.dayOfWeek) }
            .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()) ?: ""
    }

/** "Today", "Tomorrow · 3:00 PM"...: the agenda row's day and (for a timed event) its time. */
private fun agendaWhen(item: AgendaItem, context: Context): String {
    val day = agendaDay(item, context)
    return if (item.event.allDay) day else "$day · " + DateFormat.getTimeFormat(context).format(item.event.startMs)
}

/** The calendar card set to show always, with nothing coming up. */
@Composable
internal fun NoEventsCard() {
    Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.dash_no_events), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The next calendar event, with a shortcut to navigate to its place. */
@Composable
internal fun CalendarCard(event: NextEvent, onNavigate: (String) -> Unit, modifier: Modifier, whenLabel: String? = null) {
    val context = LocalContext.current
    val time = whenLabel ?: if (event.allDay) stringResource(R.string.dash_event_today) else DateFormat.getTimeFormat(context).format(event.startMs)
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(event.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (event.location.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer)
                    .carClickable(MaterialTheme.shapes.medium) { onNavigate(event.location) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Navigation, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(event.location, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Built-in wallpapers: soft gradients that keep the cards readable by day and by night. */
private val WALLPAPERS = mapOf(
    "dusk" to listOf(Color(0xFF2B1B3D), Color(0xFF6B3A5B), Color(0xFFC2735A)),
    "forest" to listOf(Color(0xFF0F2A1D), Color(0xFF1F4D36), Color(0xFF4F7A4A)),
    "ocean" to listOf(Color(0xFF071E3D), Color(0xFF1F4E79), Color(0xFF3E8DB8)),
)
val WALLPAPER_NAMES = WALLPAPERS.keys.toList()

/** The dashboard's wallpaper, filling [modifier]; nothing for "none". */
@Composable
internal fun Wallpaper(ui: CarUiState, modifier: Modifier) {
    val image = ui.wallpaperImage
    when {
        ui.wallpaper == net.prototux.aare.Settings.WALLPAPER_CUSTOM && image != null ->
            Image(image.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
        else -> WALLPAPERS[ui.wallpaper]?.let { colors -> Box(modifier.background(Brush.linearGradient(colors))) }
    }
}

/** The latest message, for the third card of very wide dashboards. */
@Composable
internal fun LatestNotification(n: CarNotice, driving: Boolean) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Notifications, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.dash_latest_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (driving) stringResource(R.string.notif_new_message_placeholder) else n.text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
