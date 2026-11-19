package net.prototux.aare.ui.car.obd

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import net.prototux.aare.R
import net.prototux.aare.obd.ObdReader
import net.prototux.aare.obdapp.CardValue
import net.prototux.aare.obdapp.ObdApp
import net.prototux.aare.obdapp.ObdAppSettings
import net.prototux.aare.obdapp.ObdHistory
import java.text.DateFormat
import java.util.Date

private enum class ObdTab(val label: Int) {
    DASH(R.string.obd_tab_dash), TRIP(R.string.obd_tab_trip), TRACK(R.string.obd_tab_track), LAP(R.string.obd_tab_lap),
    DYNO(R.string.obd_tab_dyno), HISTORY(R.string.obd_tab_history), SETTINGS(R.string.obd_tab_settings),
}

/** The reference app area the sizes are drawn for, in dp. */
private const val REF_WIDTH = 1000f
private const val REF_HEIGHT = 600f

/**
 * The OBD2 app on the car screen: everything the adapter gives, in tabs. Only listed with the
 * OBD2 setting on (see CarRuntime), so a car without an adapter never sees it.
 * Every page fits the screen at once, whatever its size or shape: nothing scrolls.
 * [location] gives the car's GPS, for the lap timer.
 */
@Composable
fun ObdAppScreen(modifier: Modifier, location: () -> Location?) {
    val ctx = LocalContext.current
    val settings = remember { ObdApp.settings(ctx) }
    var tab by rememberSaveable { mutableStateOf(ObdTab.DASH) }
    val snap by ObdApp.snapshot.collectAsState()
    val status by ObdReader.status.collectAsState()
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val scale = minOf(maxWidth.value / REF_WIDTH, maxHeight.value / REF_HEIGHT).coerceIn(0.5f, 1.6f)
        val wide = maxWidth > maxHeight * 1.2f
        // Line height follows each text's own size (the theme's is fixed, which clips the big numbers).
        CompositionLocalProvider(LocalObdScale provides scale, LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)) {
            Column(Modifier.fillMaxSize().padding(horizontal = sdp(16f), vertical = sdp(10f)), verticalArrangement = Arrangement.spacedBy(sdp(10f))) {
                // The tabs share the width (two rows on a narrow screen), so they never scroll.
                val rows = if (wide) listOf(ObdTab.entries) else ObdTab.entries.chunked(4)
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sdp(6f))) {
                        row.forEach { t -> Chip(stringResource(t.label), t == tab, Modifier.weight(1f)) { tab = t } }
                        if (row.size < 4 && !wide) repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                if (snap.simulated && snap.readings != null) Note(stringResource(R.string.obd_simulated_badge), maxLines = 1, color = ObdRed)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (snap.readings == null && tab != ObdTab.HISTORY && tab != ObdTab.SETTINGS) {
                        Text(statusText(status), Modifier.align(Alignment.Center), fontSize = ssp(20f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else when (tab) {
                        ObdTab.DASH -> DashTab(snap, settings, wide)
                        ObdTab.TRIP -> TripTab(snap, wide)
                        ObdTab.TRACK -> TrackTab(wide)
                        ObdTab.LAP -> LapTab(location, wide)
                        ObdTab.DYNO -> DynoTab(wide)
                        ObdTab.HISTORY -> HistoryTab(wide)
                        ObdTab.SETTINGS -> SettingsTab(settings, wide)
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(s: ObdReader.Status) = when (s.state) {
    ObdReader.State.OFF, ObdReader.State.CONNECTED -> stringResource(R.string.obd_waiting)
    ObdReader.State.SEARCHING -> stringResource(R.string.obd_status_searching, s.detail)
    ObdReader.State.NO_CAR -> stringResource(R.string.obd_status_no_car, s.adapter)
    ObdReader.State.GAVE_UP -> s.detail.ifBlank { stringResource(R.string.obd_status_gave_up) }
}

// --- Dashboard: two gauges around the speed, then a tile for each value the car reports ---

@Composable
private fun DashTab(snap: ObdApp.Snapshot, settings: ObdAppSettings, wide: Boolean) {
    val r = snap.readings ?: return
    val rpmMax = settings.rpmMax.toFloat()
    val redline = settings.redline.toFloat()
    val shift = (r.rpm ?: 0f) >= redline

    // A tile for each value the car reports.
    val tiles = ArrayList<@Composable (Modifier) -> Unit>()
    r.coolant?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_coolant_short), "%.0f".format(v), m, "°C", alert = v >= 110f) } }
    r.oil?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_oil), "%.0f".format(v), m, "°C", alert = v >= 130f) } }
    r.intakeAir?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_intake_air), "%.0f".format(v), m, "°C") } }
    r.outside?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_outside), "%.0f".format(v), m, "°C") } }
    // Below 11.5 V with the engine running something's wrong; a smart alternator keeps 12-ish on purpose.
    r.battery?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_battery), "%.1f".format(v), m, "V", alert = v < 11.5f) } }
    r.fuel?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_fuel), "%.0f".format(v), m, "%", alert = v < 10f) } }
    r.ethanol?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_ethanol), "%.0f".format(v), m, "%") } }
    if (snap.kmPerLiter != null || r.speed != null) tiles += { m -> Stat(stringResource(R.string.obd_fuel_use), fmt(snap.kmPerLiter, "%.1f"), m, stringResource(R.string.obd_unit_kmpl)) }
    // The gauge shows boost: load goes to a tile, and the other way round.
    if (snap.showsBoost) r.load?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_load), "%.0f".format(v), m, "%") } }
    else r.boost?.let { v -> tiles += { m -> Stat(stringResource(R.string.obd_boost), "%+.2f".format(v), m, "bar") } }
    val columns = if (wide) 5 else 3
    val tileRows = (tiles.size + columns - 1) / columns

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(10f))) {
        Row(Modifier.fillMaxWidth().weight(if (wide) 2.2f else 1.6f), horizontalArrangement = Arrangement.spacedBy(sdp(12f)), verticalAlignment = Alignment.CenterVertically) {
            Gauge(r.rpm, 0f, rpmMax, redline, fmt(r.rpm, "%.0f"), stringResource(R.string.obd_rpm), Modifier.weight(1f).fillMaxHeight())
            SpeedPanel(r.speed, snap.gear, r.pedal ?: r.throttle, shift, Modifier.weight(1f).fillMaxHeight())
            if (snap.showsBoost) {
                Gauge(snap.gauge, -1f, 2f, 1.6f, fmt(snap.gauge, "%+.2f"), stringResource(R.string.obd_boost), Modifier.weight(1f).fillMaxHeight(),
                    peak = snap.gaugePeak, subtext = snap.gaugePeak?.let { stringResource(R.string.obd_peak, "%+.2f".format(it)) } ?: "bar")
            } else {
                Gauge(snap.gauge, 0f, 100f, null, fmt(snap.gauge, "%.0f%%"), stringResource(R.string.obd_load), Modifier.weight(1f).fillMaxHeight(), peak = snap.gaugePeak)
            }
        }
        if (tileRows > 0) TileGrid(tiles, columns, Modifier.fillMaxWidth().weight(tileRows.toFloat()))
    }
}

/** Speed in the middle, with the gear and the pedal; the whole panel turns red at the red line (shift light). */
@Composable
private fun SpeedPanel(speed: Float?, gear: Int?, pedal: Float?, shift: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier.themed(MaterialTheme.shapes.large, if (shift) ObdRed else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val fg = if (shift) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
        val h = minOf(maxHeight, maxWidth * 0.8f)
        val barWidth = maxWidth * 0.55f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmt(speed, "%.0f"), fontSize = dpText(h * 0.36f), fontWeight = FontWeight.SemiBold, fontFamily = numberFont, color = fg, maxLines = 1)
            Text("km/h" + (gear?.let { "  ·  ${stringResource(R.string.obd_gear)} $it" } ?: ""), fontSize = dpText(h * 0.08f), color = fg, maxLines = 1)
            if (pedal != null) {
                Box(Modifier.padding(top = h * 0.05f).width(barWidth).height(h * 0.035f).clip(MaterialTheme.shapes.extraSmall).background(fg.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth((pedal / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(fg))
                }
                Text(stringResource(R.string.obd_pedal), fontSize = dpText(h * 0.055f), color = fg)
            }
        }
    }
}

// --- Trip ---

@Composable
private fun TripTab(snap: ObdApp.Snapshot, wide: Boolean) {
    val km = stringResource(R.string.obd_unit_kmpl)
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m -> Stat(stringResource(R.string.obd_trip_distance), "%.1f".format(snap.tripKm), m, "km") },
        { m -> Stat(stringResource(R.string.obd_trip_time), minutes(snap.tripMinutes), m) },
        { m -> Stat(stringResource(R.string.obd_trip_range), fmt(snap.rangeKm, "%.0f"), m, "km") },
        { m -> Stat(stringResource(R.string.obd_trip_average), fmt(snap.tripAverage, "%.1f"), m, km) },
        { m -> Stat(stringResource(R.string.obd_trip_now), fmt(snap.kmPerLiter, "%.1f"), m, km) },
        { m -> Stat(stringResource(R.string.obd_fuel), fmt(snap.readings?.fuel, "%.0f"), m, "%") },
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(10f))) {
        TileGrid(tiles, if (wide) 3 else 2, Modifier.fillMaxWidth().weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sdp(14f))) {
            ObdButton(stringResource(R.string.obd_trip_reset)) { ObdApp.resetTrip() }
            Note(stringResource(R.string.obd_trip_note), Modifier.weight(1f), maxLines = 3)
        }
    }
}

/** h:mm:ss, or m:ss under an hour. */
private fun minutes(m: Float): String {
    val t = (m * 60).toInt()
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

// --- History ---

@Composable
private fun HistoryTab(wide: Boolean) {
    val version by ObdApp.historyVersion.collectAsState()
    val entries = remember(version) { ObdApp.history?.read().orEmpty() }
    var confirm by remember { mutableStateOf(false) }
    if (entries.isEmpty()) {
        Text(stringResource(R.string.obd_history_empty), fontSize = ssp(18f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val records = remember(entries) { ObdHistory.records(entries) }
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(8f))) {
        if (records.isNotEmpty()) {
            Text(stringResource(R.string.obd_history_records), fontSize = ssp(18f), fontWeight = FontWeight.SemiBold)
            val cols = if (wide) 4 else 2
            val tiles = records.values.map { e -> @Composable { m: Modifier -> Stat(kindName(e.kind), valueText(e), m, footer = dates.format(Date(e.timeMs))) } }
            TileGrid(tiles, cols, Modifier.fillMaxWidth().weight((tiles.size + cols - 1) / cols * 1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.obd_history_recent), fontSize = ssp(18f), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(stringResource(if (confirm) R.string.obd_history_clear_confirm else R.string.obd_history_clear)) {
                if (confirm) { ObdApp.clearHistory(); confirm = false } else confirm = true
            }
        }
        // As many recent lines as the space holds, newest first.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(2f)) {
            val lineHeight = sdp(30f)
            val n = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
            Column {
                entries.asReversed().take(n).forEach { e ->
                    Row(Modifier.fillMaxWidth().height(lineHeight), verticalAlignment = Alignment.CenterVertically) {
                        Text(dates.format(Date(e.timeMs)), fontSize = ssp(16f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(kindName(e.kind), fontSize = ssp(16f), maxLines = 1, modifier = Modifier.weight(1f))
                        Text(valueText(e), fontSize = ssp(16f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun kindName(k: ObdHistory.Kind) = stringResource(when (k) {
    ObdHistory.Kind.TRIP -> R.string.obd_history_trip
    ObdHistory.Kind.ZERO_100 -> R.string.obd_test_0_100
    ObdHistory.Kind.KMH_80_120 -> R.string.obd_test_80_120
    ObdHistory.Kind.QUARTER_MILE -> R.string.obd_test_quarter
    ObdHistory.Kind.BRAKE_100_0 -> R.string.obd_test_brake
    ObdHistory.Kind.LAP -> R.string.obd_history_lap
    ObdHistory.Kind.DYNO -> R.string.obd_history_dyno
})

@Composable
private fun valueText(e: ObdHistory.Entry) = when (e.kind) {
    ObdHistory.Kind.TRIP -> "%.1f km".format(e.value) + if (e.second > 0f) " · %.1f %s".format(e.second, stringResource(R.string.obd_unit_kmpl)) else ""
    ObdHistory.Kind.BRAKE_100_0 -> "%.1f m".format(e.value)
    ObdHistory.Kind.QUARTER_MILE -> "%.2f s".format(e.value) + if (e.second > 0f) " · %.0f km/h".format(e.second) else ""
    ObdHistory.Kind.LAP -> time(e.value)
    ObdHistory.Kind.DYNO -> "%.0f hp".format(e.value)
    else -> "%.2f s".format(e.value)
}

// --- Settings: two columns side by side on a wide screen, all at once ---

@Composable
private fun SettingsTab(settings: ObdAppSettings, wide: Boolean) {
    var engine by remember { mutableStateOf(settings.engine) }
    var rpmMax by remember { mutableIntStateOf(settings.rpmMax) }
    var redline by remember { mutableIntStateOf(settings.redline) }
    var cc by remember { mutableIntStateOf(settings.displacementCc) }
    var tank by remember { mutableIntStateOf(settings.tankLiters) }
    var fuel by remember { mutableStateOf(settings.fuel) }
    var ethanol by remember { mutableIntStateOf(settings.ethanolPercent) }
    var mass by remember { mutableIntStateOf(settings.massKg) }
    var gears by remember { mutableIntStateOf(ObdApp.learnedGears) }
    var picking by remember { mutableStateOf(false) }
    if (picking) { CardPicker(settings, wide) { picking = false }; return }
    val items = listOf<@Composable (Modifier) -> Unit>(
        { m ->
            Choices(stringResource(R.string.obd_set_engine), listOf(ObdAppSettings.Engine.AUTO to R.string.obd_set_engine_auto,
                ObdAppSettings.Engine.TURBO to R.string.obd_set_engine_turbo, ObdAppSettings.Engine.ASPIRATED to R.string.obd_set_engine_na), engine, m) { engine = it; settings.engine = it }
        },
        { m ->
            Choices(stringResource(R.string.obd_set_fuel), listOf(ObdAppSettings.Fuel.PETROL to R.string.obd_set_fuel_petrol,
                ObdAppSettings.Fuel.DIESEL to R.string.obd_set_fuel_diesel), fuel, m) { fuel = it; settings.fuel = it }
        },
        { m -> Stepper(stringResource(R.string.obd_set_rpm_max), rpmMax, "rpm", 500, 4000..10000, modifier = m) { rpmMax = it; settings.rpmMax = it; if (redline > it) { redline = it; settings.redline = it } } },
        { m -> Stepper(stringResource(R.string.obd_set_redline), redline, "rpm", 100, 2000..rpmMax, bigStep = 500, modifier = m) { redline = it; settings.redline = it } },
        { m -> Stepper(stringResource(R.string.obd_set_displacement), cc, "cm³", 100, 600..8000, bigStep = 1000, modifier = m) { cc = it; settings.displacementCc = it } },
        { m -> Stepper(stringResource(R.string.obd_set_ethanol), ethanol, "%", 1, 0..100, bigStep = 10, modifier = m) { ethanol = it; settings.ethanolPercent = it } },
        { m -> Stepper(stringResource(R.string.obd_set_tank), tank, "L", 1, 20..150, bigStep = 10, modifier = m) { tank = it; settings.tankLiters = it } },
        { m -> Stepper(stringResource(R.string.obd_set_mass), mass, "kg", 10, 500..4000, bigStep = 100, modifier = m) { mass = it; settings.massKg = it } },
        { m ->
            Column(m, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.obd_set_card), fontSize = ssp(17f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Chip(stringResource(R.string.obd_set_card_pick), modifier = Modifier.padding(top = sdp(6f))) { picking = true }
            }
        },
        { m ->
            Column(m, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.obd_set_gears, gears), fontSize = ssp(17f), maxLines = 1)
                Chip(stringResource(R.string.obd_set_gears_forget), modifier = Modifier.padding(top = sdp(6f))) { ObdApp.forgetGears(); gears = 0 }
            }
        },
    )
    Column(Modifier.fillMaxSize()) {
        val cols = if (wide) 2 else 1
        Column(Modifier.fillMaxWidth().weight(1f)) {
            items.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(sdp(20f))) {
                    row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        Note(stringResource(R.string.obd_set_note), maxLines = 2)
    }
}

/**
 * Which values the dashboard's Car card shows: tapped in order, up to [CardValue.MAX]; each picked
 * one shows its place. All of them on one screen, like the rest of the app.
 */
@Composable
private fun CardPicker(settings: ObdAppSettings, wide: Boolean, onDone: () -> Unit) {
    var picked by remember { mutableStateOf(settings.cardValues) }
    val tiles = CardValue.entries.map { v ->
        @Composable { m: Modifier ->
            val place = picked.indexOf(v)
            val selected = place >= 0
            Row(
                m.themed(MaterialTheme.shapes.small, if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        picked = if (selected) picked - v else if (picked.size < CardValue.MAX) picked + v else picked
                        settings.cardValues = picked
                    }.padding(horizontal = sdp(12f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                Icon(v.icon, null, tint = fg, modifier = Modifier.size(sdp(26f)))
                Text(stringResource(v.label), fontSize = ssp(17f), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = sdp(8f)))
                if (selected) Text("${place + 1}", fontSize = ssp(20f), fontWeight = FontWeight.Bold, color = fg)
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(10f))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sdp(14f))) {
            Note(stringResource(R.string.obd_set_card_hint, CardValue.MAX), Modifier.weight(1f), maxLines = 2)
            ObdButton(stringResource(R.string.obd_done), primary = true, onClick = onDone)
        }
        TileGrid(tiles, if (wide) 4 else 2, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun <T> Choices(title: String, options: List<Pair<T, Int>>, selected: T, modifier: Modifier, onSelect: (T) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = ssp(17f), maxLines = 1)
        Row(Modifier.padding(top = sdp(6f)), horizontalArrangement = Arrangement.spacedBy(sdp(6f))) {
            options.forEach { (v, label) -> Chip(stringResource(label), v == selected) { onSelect(v) } }
        }
    }
}
