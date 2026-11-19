package net.prototux.aare.ui.car.obd

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.obdapp.ObdApp
import net.prototux.aare.obdapp.track.Acceleration
import net.prototux.aare.obdapp.track.Braking
import net.prototux.aare.obdapp.track.LapTimer
import net.prototux.aare.obdapp.track.QuarterMile
import net.prototux.aare.obdapp.track.SpeedTest

/** Ticks [ms] times a second while shown: the timers run between the adapter's readings. */
@Composable
private fun ticker(ms: Long = 100L): Long {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(ms); value = System.currentTimeMillis() } }
    return now
}

/** Two halves: side by side on a wide screen, one above the other on a tall one. */
@Composable
private fun Halves(wide: Boolean, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(sdp(16f))) {
        first(Modifier.weight(1f).fillMaxHeight()); second(Modifier.weight(1.2f).fillMaxHeight())
    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(12f))) {
        first(Modifier.weight(1f).fillMaxWidth()); second(Modifier.weight(1f).fillMaxWidth())
    }
}

// --- Track: G force from the phone, and the timed runs ---

@Composable
internal fun TrackTab(wide: Boolean) {
    val ctx = LocalContext.current
    // The accelerometer runs only while this page is open.
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = ObdApp.accelerometer(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(l) }
    }
    val now = ticker()
    val g = ObdApp.g
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(8f))) {
        Warning(stringResource(R.string.obd_track_warning))
        Halves(wide, { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(sdp(4f))) {
                Text(stringResource(R.string.obd_g_title), fontSize = ssp(17f))
                GDiagram(now, Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true))
                Text(stringResource(R.string.obd_g_accel, "%.2f".format(g.maxAccel)) + "  " + stringResource(R.string.obd_g_brake, "%.2f".format(g.maxBrake)) +
                    "  " + stringResource(R.string.obd_g_lateral, "%.2f".format(g.maxLateral)), fontSize = ssp(15f), maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sdp(10f))) {
                    Chip(stringResource(R.string.obd_g_reset)) { ObdApp.edit { g.resetMax() } }
                    Note(stringResource(R.string.obd_g_hint), Modifier.weight(1f, fill = false))
                }
            }
        }, { m ->
            TileGrid(ObdApp.tests.all.map { t -> @Composable { mm: Modifier -> TestTile(t, now, mm) } }, if (wide) 1 else 2, m)
        })
    }
}

@Composable
private fun TestTile(t: SpeedTest, now: Long, modifier: Modifier) {
    val running = t.runningSeconds(now)
    val value = when {
        running != null && t is QuarterMile -> "%.0f m".format(t.traveled)
        running != null && t is Braking -> "%.0f m".format(t.traveled)
        running != null -> "%.1f s".format(running)
        t is Braking -> fmt(t.result, "%.1f m")
        else -> fmt(t.result, "%.2f s")
    }
    val state = when (t.state) {
        SpeedTest.State.OFF -> stringResource(R.string.obd_test_off)
        SpeedTest.State.WAITING -> when (t) {
            is Braking -> stringResource(R.string.obd_test_above, 100)
            is Acceleration -> if (t.from == 0f) stringResource(R.string.obd_test_stop) else stringResource(R.string.obd_test_below, (t.from - 5).toInt())
            else -> stringResource(R.string.obd_test_stop)
        }
        SpeedTest.State.READY -> stringResource(if (t is Braking) R.string.obd_test_ready_brake else R.string.obd_test_ready)
        SpeedTest.State.RUNNING -> stringResource(R.string.obd_test_running)
    }
    val best = t.best?.let { stringResource(R.string.obd_best, if (t is Braking) "%.1f m".format(it) else "%.2f s".format(it)) }
    Stat(kindName(t.kind), value, modifier, alert = t.armed, onClick = { ObdApp.edit { tests.toggle(t) } },
        footer = listOfNotNull(state, best).joinToString(" · "))
}

/** The G diagram: rings at 0.5 and 1 G, the recent trail and the current dot (right = right turn, up = speeding up). */
@Composable
private fun GDiagram(@Suppress("UNUSED_PARAMETER") tick: Long, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.surfaceContainerHighest
    val dot = MaterialTheme.colorScheme.primary
    val trailColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val g = ObdApp.g
    val trail = ObdApp.edit { g.trail.toList() }
    val now = ObdApp.edit { g.lateral to g.longitudinal }
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * 0.95f // 1.2 G at the edge
        val scale = r / 1.2f
        val line = size.minDimension / 150f
        drawCircle(ring, scale * 1f, c, style = Stroke(line * 1.5f))
        drawCircle(ring, scale * 0.5f, c, style = Stroke(line))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), line)
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), line)
        fun at(p: Pair<Float, Float>) = Offset(c.x + p.first.coerceIn(-1.2f, 1.2f) * scale, c.y - p.second.coerceIn(-1.2f, 1.2f) * scale)
        trail.forEach { drawCircle(trailColor, line * 3f, at(it)) }
        drawCircle(dot, line * 8f, at(now))
    }
}

// --- Lap timer (GPS) ---

@Composable
internal fun LapTab(location: () -> Location?, wide: Boolean) {
    val now = ticker()
    val laps = ObdApp.laps
    // Feeds the car's GPS points while the page is open; each new fix once.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val l = location()
            if (l != null && l.time != last) {
                last = l.time
                ObdApp.location(l.latitude, l.longitude, System.currentTimeMillis(), if (l.hasSpeed()) l.speed * 3.6f else (ObdApp.snapshot.value.readings?.speed ?: 0f))
            }
            delay(200)
        }
    }
    val hasGps = location() != null
    val state = ObdApp.edit { laps.state }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(8f))) {
        Warning(stringResource(R.string.obd_track_warning))
        Halves(wide, { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(sdp(8f))) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    Text(time(ObdApp.edit { laps.secondsOnLap(now) }), fontSize = dpText(minOf(maxHeight * 0.8f, maxWidth * 0.2f)), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                when {
                    !hasGps -> Note(stringResource(R.string.obd_lap_no_gps), maxLines = 1, color = ObdRed)
                    state == LapTimer.State.WAITING_START -> Note(stringResource(R.string.obd_lap_waiting))
                }
                val d = ObdApp.edit { laps.delta() }
                TileGrid(listOf(
                    { mm -> Stat(stringResource(R.string.obd_lap_last), time(laps.last), mm) },
                    { mm -> Stat(stringResource(R.string.obd_lap_best), time(laps.best), mm) },
                    { mm -> Stat(stringResource(R.string.obd_lap_delta), fmt(d, "%+.2f"), mm, "s", alert = d != null && d > 0f) },
                    { mm -> Stat(stringResource(R.string.obd_lap_count), "${laps.laps}", mm) },
                ), 2, Modifier.fillMaxWidth().weight(2f))
                ObdButton(stringResource(if (state == LapTimer.State.OFF) R.string.obd_lap_arm else R.string.obd_lap_disarm), Modifier.fillMaxWidth(), primary = state == LapTimer.State.OFF) {
                    ObdApp.edit { if (laps.state == LapTimer.State.OFF) laps.arm() else laps.disarm() }
                }
            }
        }, { m -> TrackOutline(now, m) })
    }
}

/** The best lap's line (or the lap so far) and where the car is on it. */
@Composable
private fun TrackOutline(@Suppress("UNUSED_PARAMETER") tick: Long, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val car = MaterialTheme.colorScheme.primary
    val points = ObdApp.edit { laps.outline }
    val here = ObdApp.edit { laps.currentLap.lastOrNull() }
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val scale = minOf(size.width / (maxX - minX).coerceAtLeast(1f), size.height / (maxY - minY).coerceAtLeast(1f)) * 0.9f
        val ox = (size.width - (maxX - minX) * scale) / 2; val oy = (size.height - (maxY - minY) * scale) / 2
        fun at(x: Float, y: Float) = Offset(ox + (x - minX) * scale, size.height - (oy + (y - minY) * scale))
        val path = Path()
        points.forEachIndexed { i, p -> val o = at(p.x, p.y); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, line, style = Stroke(size.minDimension / 80f, cap = StrokeCap.Round))
        here?.let { drawCircle(car, size.minDimension / 40f, at(it.x, it.y)) }
    }
}

// --- Dyno ---

@Composable
internal fun DynoTab(wide: Boolean) {
    val now = ticker(250L)
    val dyno = ObdApp.dyno
    val armed = ObdApp.edit { dyno.armed }
    val peak = ObdApp.edit { dyno.peakHp to dyno.peakRpm }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sdp(8f))) {
        Warning(stringResource(R.string.obd_track_warning))
        Halves(wide, { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(sdp(10f))) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    Text(if (peak.first > 0f) stringResource(R.string.obd_dyno_peak, peak.first, peak.second) else DASH,
                        fontSize = dpText(minOf(maxHeight * 0.6f, maxWidth * 0.1f)), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(stringResource(if (armed) R.string.obd_dyno_armed else R.string.obd_dyno_off), fontSize = ssp(18f), maxLines = 2,
                    color = if (armed) ObdRed else MaterialTheme.colorScheme.onSurface)
                ObdButton(stringResource(if (armed) R.string.obd_dyno_disarm else R.string.obd_dyno_arm), Modifier.fillMaxWidth(), primary = !armed) { ObdApp.edit { dyno.toggle() } }
                Note(stringResource(R.string.obd_dyno_note), maxLines = 3)
            }
        }, { m -> DynoCurve(now, m) })
    }
}

/** Power by rpm band, as bars. */
@Composable
private fun DynoCurve(@Suppress("UNUSED_PARAMETER") tick: Long, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.surfaceContainerHighest
    val curve = ObdApp.edit { dyno.curve.copyOf() }
    Canvas(modifier) {
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), size.minDimension / 100f)
        val top = curve.maxOrNull()?.takeIf { it > 0f } ?: return@Canvas
        val w = size.width / curve.size
        curve.forEachIndexed { i, hp ->
            if (hp <= 0f) return@forEachIndexed
            val h = hp / top * size.height * 0.92f
            drawRect(bar, Offset(i * w + w * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(w * 0.7f, h))
        }
    }
}
