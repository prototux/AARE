package net.prototux.aare.ui.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.car.CarData
import net.prototux.aare.car.Weather
import kotlin.math.roundToInt

/** A status item the rail or taskbar can show, chosen and ordered on the phone. */
enum class RailWidget(
    /** Stored in the settings: never change it once released. */
    val id: String,
) {
    CLOCK("clock"),
    TEMPERATURE("temperature"),
    BATTERY("battery"),
    WEATHER("weather");

    companion object {
        /** The widgets of a saved list of ids, in order: unknown ids dropped, each widget once. */
        fun parse(ids: List<String>): List<RailWidget> = ids.mapNotNull { id -> entries.firstOrNull { it.id == id } }.distinct()

        /** Before the rail was configurable: the clock, the phone's battery and the weather (Settings.railWidgets' own default). */
        val DEFAULT = listOf(CLOCK, BATTERY, WEATHER)
    }
}

/** This order with [widget] shown (added at the end) or hidden. */
internal fun List<RailWidget>.withWidget(widget: RailWidget, shown: Boolean): List<RailWidget> = when {
    shown -> if (widget in this) this else this + widget
    else -> this - widget
}

/**
 * The rail's chosen status widgets, laid out for the layout they're in: stacked vertically on a
 * side rail, in a row on a bottom bar (horizontal), each [spacing] apart.
 */
@Composable
internal fun RailWidgets(widgets: List<RailWidget>, weather: Weather?, horizontal: Boolean, spacing: Dp) {
    val content: @Composable () -> Unit = {
        widgets.forEach { widget ->
            when (widget) {
                RailWidget.CLOCK -> Clock()
                RailWidget.TEMPERATURE -> OutsideTemperatureWidget(weather)
                RailWidget.BATTERY -> BatteryWidget()
                RailWidget.WEATHER -> WeatherChip(weather)
            }
        }
    }
    if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) { content() }
    else Column(verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

/**
 * The temperature outside, from the car's own sensor when it reports one; else the weather the
 * dashboard already fetches (same source as the weather widget, with its icon too).
 */
@Composable
internal fun OutsideTemperatureWidget(weather: Weather?) {
    val car by CarData.state.collectAsState()
    val carTemperature = car.environment?.value?.temperatureC
    if (carTemperature != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Thermostat, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            // Always Celsius: that's the unit AAP's Environment sensor reports, unlike the fetched
            // weather (which follows the phone's locale).
            Text("${carTemperature.roundToInt()}°C", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        WeatherChip(weather)
    }
}

/** Just the phone's battery (gauge, charging bolt or percent): one of the rail's status widgets. */
@Composable
internal fun BatteryWidget() {
    val s = rememberPhoneStatus() ?: return
    val fg = MaterialTheme.colorScheme.onSurface
    val dim = fg.copy(alpha = 0.25f)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        BatteryGauge(s.battery, s.charging, fg, dim)
        if (s.charging) Icon(Icons.Rounded.Bolt, stringResource(R.string.phone_status_charging), tint = fg, modifier = Modifier.size(14.dp))
        else Text("${s.battery}", style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/**
 * The rail's status widgets, for the car's and the phone's settings: each shown or hidden, and the
 * shown ones moved up or down. Mirrors DashboardCardsEditor, for the dashboard's own cards.
 */
@Composable
internal fun RailWidgetsEditor(order: List<RailWidget>, rowHeight: Dp, onChange: (List<RailWidget>) -> Unit) {
    Column {
        Text(stringResource(R.string.rail_widgets_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.rail_widgets_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (order + RailWidget.entries.filter { it !in order }).forEach { widget ->
            val i = order.indexOf(widget)
            val shown = i >= 0
            Row(Modifier.fillMaxWidth().heightIn(min = rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = rowHeight).clickable { onChange(order.withWidget(widget, !shown)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = shown, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(widget.label), style = MaterialTheme.typography.bodyLarge)
                }
                if (shown) {
                    IconButton(onClick = { onChange(order.movedBy(i, -1)) }, enabled = i > 0, modifier = Modifier.size(rowHeight)) {
                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.common_up))
                    }
                    IconButton(onClick = { onChange(order.movedBy(i, 1)) }, enabled = i < order.lastIndex, modifier = Modifier.size(rowHeight)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.common_down))
                    }
                }
            }
        }
    }
}

private val RailWidget.label: Int
    get() = when (this) {
        RailWidget.CLOCK -> R.string.rail_widget_clock
        RailWidget.TEMPERATURE -> R.string.rail_widget_temperature
        RailWidget.BATTERY -> R.string.rail_widget_battery
        RailWidget.WEATHER -> R.string.rail_widget_weather
    }
