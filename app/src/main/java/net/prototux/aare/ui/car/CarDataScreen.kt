package net.prototux.aare.ui.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.car.CarData
import net.prototux.aare.service.ProjectionService

/**
 * A read-only diagnostics screen on the car: the same live data as the phone's "Car data" page
 * (speed, gear, fuel, sensors…), rendered in the car UI so it can be checked from the head unit.
 * All values come from the self-updating [CarData.state] and [ProjectionService.state].
 */
@Composable
internal fun CarDataScreen(modifier: Modifier) {
    val car by CarData.state.collectAsState()
    val proj by ProjectionService.state.collectAsState()
    val rows = carDataLines(car, proj)
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.car_data_not_connected), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows) { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

/** (label, value) for every datum the car has reported. Mirrors the phone viewer's rows. */
@Composable
private fun carDataLines(car: CarData.State, proj: ProjectionService.State): List<Pair<String, String>> {
    if (!car.connected) return emptyList()
    val rows = ArrayList<Pair<String, String>>()
    fun add(label: String, value: String?) { if (value != null) rows += label to value }
    val on = stringResource(R.string.car_data_on); val off = stringResource(R.string.car_data_off)
    val yes = stringResource(R.string.car_data_yes); val no = stringResource(R.string.car_data_no)
    fun onOff(b: Boolean?) = b?.let { if (it) on else off }
    fun yesNo(b: Boolean?) = b?.let { if (it) yes else no }

    add(stringResource(R.string.car_data_identity),
        car.identity?.let { listOfNotNull(it.make.ifBlank { null }, it.model.ifBlank { null }, it.year?.toString()).joinToString(" ").ifBlank { null } })
    add(stringResource(R.string.car_data_head_unit), proj.headUnit.ifBlank { null })
    add(stringResource(R.string.car_data_connection), proj.transport.ifBlank { null })
    add(stringResource(R.string.car_data_video), proj.video.ifBlank { null })
    add(stringResource(R.string.car_data_driving), car.drivingStatus?.let { if (it.value == 0) stringResource(R.string.car_data_parked) else stringResource(R.string.car_data_moving) })
    add(stringResource(R.string.car_data_night), car.nightMode?.let { if (it.value) stringResource(R.string.car_data_night_on) else stringResource(R.string.car_data_night_off) })
    add(stringResource(R.string.car_data_speed), car.speed?.let { "%.0f km/h".format(it.value.metersPerSecond * 3.6f) })
    add(stringResource(R.string.car_data_cruise), car.speed?.value?.cruiseSetSpeed?.let { "$it" })
    add(stringResource(R.string.car_data_rpm), car.rpm?.let { "%.0f".format(it.value) })
    add(stringResource(R.string.car_data_gear), car.gearName)
    add(stringResource(R.string.car_data_parking_brake), onOff(car.parkingBrake?.value))
    add(stringResource(R.string.car_data_odometer), car.odometer?.let { o -> "%.0f km".format(o.value.km) + (o.value.tripKm?.let { " · trip %.0f km".format(it) } ?: "") })
    add(stringResource(R.string.car_data_fuel), car.fuel?.let { f ->
        listOfNotNull(f.value.levelPercent?.let { "$it%" }, f.value.rangeKm?.let { "$it km" }, if (f.value.low == true) stringResource(R.string.car_data_low) else null).joinToString(" · ").ifBlank { null } })
    add(stringResource(R.string.car_data_compass), car.compass?.let { "%.0f°".format(it.value.bearingDeg) })
    add(stringResource(R.string.car_data_satellites), car.satellites?.let { s -> "${s.value.inUse}" + (s.value.inView?.let { " / $it" } ?: "") })
    add(stringResource(R.string.car_data_environment), car.environment?.let { e ->
        listOfNotNull(e.value.temperatureC?.let { "%.1f °C".format(it) }, e.value.pressureKpa?.let { "%.0f kPa".format(it) }, e.value.rain?.let { if (it > 0) stringResource(R.string.car_data_rain) else null }).joinToString(" · ").ifBlank { null } })
    add(stringResource(R.string.car_data_climate), car.hvac?.let { h ->
        listOfNotNull(h.value.targetC?.let { "→ %.0f °C".format(it) }, h.value.currentC?.let { "%.0f °C".format(it) }).joinToString(" · ").ifBlank { null } })
    add(stringResource(R.string.car_data_steering), car.deadReckoning?.value?.steeringAngleDeg?.let { "%.0f°".format(it) })
    add(stringResource(R.string.car_data_tyres), car.tirePressuresKpa?.let { t -> t.value.joinToString(" · ") { "%.0f".format(it) } + " kPa" })
    add(stringResource(R.string.car_data_doors), car.doors?.let { d ->
        val open = buildList {
            if (d.value.hoodOpen == true) add(stringResource(R.string.car_data_hood))
            if (d.value.trunkOpen == true) add(stringResource(R.string.car_data_trunk))
            repeat(d.value.doorsOpen.count { it }) { add(stringResource(R.string.car_data_door)) }
        }
        if (open.isEmpty()) stringResource(R.string.car_data_closed) else open.joinToString(", ") })
    add(stringResource(R.string.car_data_lights), car.lights?.let { l ->
        listOfNotNull(l.value.headLights?.name?.lowercase(), l.value.turn?.takeIf { it != CarData.Turn.NONE }?.name?.lowercase(),
            if (l.value.hazard == true) stringResource(R.string.car_data_hazard) else null).joinToString(" · ").ifBlank { null } })
    add(stringResource(R.string.car_data_passenger), yesNo(car.passengerPresent?.value))
    add(stringResource(R.string.car_data_toll), yesNo(car.tollCardPresent?.value))
    add(stringResource(R.string.car_data_accelerometer), car.accelerometer?.let { a -> xyz(a.value) }?.let { "$it m/s²" })
    add(stringResource(R.string.car_data_gyroscope), car.gyroscope?.let { g -> xyz(g.value) }?.let { "$it rad/s" })
    add(stringResource(R.string.car_data_dtc), car.diagnostics?.value?.takeIf { it.isNotEmpty() }?.joinToString(" ") { "%02x".format(it) })
    return rows
}

private fun xyz(v: CarData.Vector3): String? =
    listOf(v.x, v.y, v.z).let { if (it.all { c -> c == null }) null else it.joinToString(", ") { c -> c?.let { "%.2f".format(it) } ?: "–" } }
