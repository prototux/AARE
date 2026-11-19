package net.prototux.aare.ui.car.obd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Grass
import androidx.compose.material.icons.rounded.OilBarrel
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.obdapp.CardValue
import net.prototux.aare.obdapp.ObdApp

/** One value of the card: its icon (named for screen readers), the number and its unit. */
private class Reading(val icon: ImageVector, val label: String, val value: String, val unit: String)

/**
 * The Cockpit's dashboard card: the speed with the gear, the rev counter as a bar with its scale
 * under it (the range and the red line from the app's settings), and a tile for each value.
 * In the theme's own colours, like the other dashboard cards. Shown only while the adapter gives
 * readings (CarShell); [compact] for a thin strip.
 */
@Composable
internal fun ObdDashCard(snap: ObdApp.Snapshot, compact: Boolean) {
    val r = snap.readings ?: return
    val ctx = LocalContext.current
    val settings = remember { ObdApp.settings(ctx) }
    val rpmMax = settings.rpmMax
    val redline = settings.redline

    // The values picked in the app's settings, in their order; one the car doesn't report is left out.
    val kmpl = stringResource(R.string.obd_unit_kmpl)
    val readings = settings.cardValues.mapNotNull { v ->
        val label = stringResource(v.label)
        when (v) {
            CardValue.COOLANT -> r.coolant?.let { Reading(v.icon, label, "%.0f".format(it), "°C") }
            CardValue.OIL -> r.oil?.let { Reading(v.icon, label, "%.0f".format(it), "°C") }
            CardValue.INTAKE -> r.intakeAir?.let { Reading(v.icon, label, "%.0f".format(it), "°C") }
            CardValue.OUTSIDE -> r.outside?.let { Reading(v.icon, label, "%.0f".format(it), "°C") }
            CardValue.GAUGE -> snap.gauge?.let { if (snap.showsBoost) Reading(v.icon, label, "%+.2f".format(it), "bar") else Reading(v.icon, label, "%.0f".format(it), "%") }
            CardValue.BATTERY -> r.battery?.let { Reading(v.icon, label, "%.1f".format(it), "V") }
            CardValue.FUEL -> r.fuel?.let { Reading(v.icon, label, "%.0f".format(it), "%") }
            CardValue.ETHANOL -> r.ethanol?.let { Reading(v.icon, label, "%.0f".format(it), "%") }
            CardValue.RANGE -> snap.rangeKm?.let { Reading(v.icon, label, "%.0f".format(it), "km") }
            CardValue.AVERAGE -> snap.tripAverage?.let { Reading(v.icon, label, "%.1f".format(it), kmpl) }
            CardValue.NOW -> snap.kmPerLiter?.let { Reading(v.icon, label, "%.1f".format(it), kmpl) }
        }
    }
    val speed = r.speed?.let { "%.0f".format(it) } ?: DASH
    val gear = snap.gear?.let { "${stringResource(R.string.obd_gear)} $it" }

    // Line heights follow each text's size (the theme's fixed ones add empty rows on small text).
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Everything grows with the card, drawn for a 330 x 420 dp one.
            val k = minOf(maxWidth.value / 330f, maxHeight.value / 420f).coerceIn(0.6f, 2f)
            val stripHeight = maxHeight
            val narrow = maxWidth < 320.dp
            if (compact) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(speed, fontSize = text(minOf(stripHeight * 0.45f, 44.dp)), fontWeight = FontWeight.Bold, fontFamily = numberFont, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text("km/h", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        RevBar(r.rpm, rpmMax, redline, Modifier.fillMaxWidth().height(10.dp))
                        RevScale(rpmMax, text(11.dp))
                    }
                }
                return@BoxWithConstraints
            }
            Column(Modifier.fillMaxSize().padding(horizontal = (22 * k).dp, vertical = (16 * k).dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(speed, fontSize = text((64 * k).dp), fontWeight = FontWeight.Bold, fontFamily = numberFont, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Spacer(Modifier.width((10 * k).dp))
                    // The gear over the unit, so a wide font (the digital-dash one) still leaves room.
                    Column(Modifier.padding(bottom = (8 * k).dp)) {
                        if (gear != null) Text(gear, fontSize = text((18 * k).dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        Text("km/h", fontSize = text((18 * k).dp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.height((8 * k).dp))
                RevBar(r.rpm, rpmMax, redline, Modifier.fillMaxWidth().height((12 * k).dp))
                RevScale(rpmMax, text(maxOf(13 * k, 11f).dp), Modifier.padding(top = (3 * k).dp))
                Spacer(Modifier.height((12 * k).dp))
                // The tiles take the height left: three to a row, two on a narrow card (in three rows).
                val perRow = if (narrow) 2 else 3
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((8 * k).dp)) {
                    readings.chunked(perRow).forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy((8 * k).dp)) {
                            row.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
                            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Each value's icon on the card (and its name, in the settings and for screen readers). */
internal val CardValue.icon: ImageVector get() = when (this) {
    CardValue.COOLANT -> Icons.Rounded.Thermostat
    CardValue.OIL -> Icons.Rounded.OilBarrel
    CardValue.INTAKE -> Icons.Rounded.Waves
    CardValue.OUTSIDE -> Icons.Rounded.DeviceThermostat
    CardValue.GAUGE -> Icons.Rounded.Air
    CardValue.BATTERY -> Icons.Rounded.BatteryChargingFull
    CardValue.FUEL -> Icons.Rounded.LocalGasStation
    CardValue.ETHANOL -> Icons.Rounded.Grass
    CardValue.RANGE -> Icons.Rounded.Route
    CardValue.AVERAGE -> Icons.Rounded.Eco
    CardValue.NOW -> Icons.Rounded.ShowChart
}

internal val CardValue.label: Int get() = when (this) {
    CardValue.COOLANT -> R.string.obd_coolant
    CardValue.OIL -> R.string.obd_oil
    CardValue.INTAKE -> R.string.obd_intake_air
    CardValue.OUTSIDE -> R.string.obd_outside
    CardValue.GAUGE -> R.string.obd_card_gauge
    CardValue.BATTERY -> R.string.obd_battery
    CardValue.FUEL -> R.string.obd_fuel
    CardValue.ETHANOL -> R.string.obd_ethanol
    CardValue.RANGE -> R.string.obd_trip_range
    CardValue.AVERAGE -> R.string.obd_trip_average
    CardValue.NOW -> R.string.obd_card_now
}

/** The revs as a bar over the scale's track, with the red zone shaded; the bar turns red in it. */
@Composable
private fun RevBar(rpm: Float?, max: Int, redline: Int, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.onSurface
    val red = ObdRed
    // The theme's corners (square on some), the bar's fill cut by them too.
    Canvas(modifier.clip(MaterialTheme.shapes.extraSmall)) {
        drawRect(track)
        val redStart = size.width * (redline.toFloat() / max).coerceIn(0f, 1f)
        drawRect(red.copy(alpha = 0.35f), topLeft = Offset(redStart, 0f), size = Size(size.width - redStart, size.height))
        if (rpm != null) {
            val w = size.width * (rpm / max).coerceIn(0f, 1f)
            drawRect(if (w > redStart) red else fill, size = Size(w, size.height))
        }
    }
}

/** The thousands under the bar: 0 1 2 … up to the rev counter's range. */
@Composable
private fun RevScale(max: Int, size: TextUnit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0..max / 1000) Text("$i", fontSize = size, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One value: the icon on top, the number big under it with its unit, sized to the tile. */
@Composable
private fun Tile(r: Reading, modifier: Modifier) {
    BoxWithConstraints(modifier.themed(MaterialTheme.shapes.small, MaterialTheme.colorScheme.surfaceContainerHigh)) {
        val k = minOf(maxHeight.value / 70f, maxWidth.value / 105f).coerceIn(0.7f, 1.8f)
        // The number and its unit must fit the width, whatever the theme's font ("+0.03 bar" in a wide one).
        val inner = maxWidth.value - 18 * k
        val valueSize = minOf(27 * k, inner / (r.value.length * 0.66f + r.unit.length * 0.36f + 0.3f))
        Column(Modifier.fillMaxSize().padding(horizontal = (9 * k).dp, vertical = (7 * k).dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(r.icon, contentDescription = r.label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((26 * k).dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(r.value, fontSize = text(valueSize.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Spacer(Modifier.width((3 * k).dp))
                Text(r.unit, fontSize = text((12 * k).dp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.padding(bottom = (3 * k).dp))
            }
        }
    }
}

@Composable
private fun text(d: Dp): TextUnit = with(LocalDensity.current) { d.toSp() }
