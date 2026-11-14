package net.prototux.aare.host

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import net.prototux.aare.car.CarData
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What the car's own sensors say, for the assistant's car_status: only the values the car has
 * actually reported, in units people talk in (km/h, km, °C, kPa), with a few ready-made warnings
 * so the model doesn't have to judge raw numbers.
 */
object VehicleStatus {

    /** The sensors [json] reads: the car is asked for them while the assistant is in use. */
    val SENSORS: Set<SensorType> = setOf(
        SensorType.SENSOR_SPEED, SensorType.SENSOR_GEAR, SensorType.SENSOR_PARKING_BRAKE, SensorType.SENSOR_RPM,
        SensorType.SENSOR_ODOMETER, SensorType.SENSOR_FUEL, SensorType.SENSOR_ENVIRONMENT_DATA, SensorType.SENSOR_HVAC_DATA,
        SensorType.SENSOR_DOOR_DATA, SensorType.SENSOR_LIGHT_DATA, SensorType.SENSOR_TIRE_PRESSURE_DATA,
        SensorType.SENSOR_PASSENGER_DATA, SensorType.SENSOR_TOLL_CARD, SensorType.SENSOR_OBDII_DIAGNOSTIC_CODE,
    )

    /** Values older than this are left out: the car stopped sending them. */
    private const val STALE_MS = 10 * 60_000L

    fun json(s: CarData.State, now: Long = System.currentTimeMillis()): JSONObject? {
        if (!s.connected) return null
        // [eventDriven] sensors (doors, lights, gear...) only arrive on change: a car that hasn't
        // touched one in 10 minutes just hasn't changed it, so never drop those for age.
        fun <T> CarData.Timed<T>?.fresh(eventDriven: Boolean = false): T? =
            this?.takeIf { eventDriven || now - it.atMillis < STALE_MS }?.value
        val o = JSONObject()
        val warnings = JSONArray()

        s.identity?.let { id ->
            listOf(id.make, id.model, id.year?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
                .takeIf { it.isNotBlank() }?.let { o.put("model", it) }
        }
        s.speed.fresh()?.let { o.put("speed_kmh", (it.metersPerSecond * 3.6f).roundToInt()); if (it.cruiseEngaged == true) o.put("cruise_control", true) }
        s.gearName?.takeIf { s.gear.fresh(eventDriven = true) != null }?.let { o.put("gear", it) }
        s.parkingBrake.fresh(eventDriven = true)?.let { o.put("parking_brake", it) }
        s.rpm.fresh()?.let { o.put("engine_rpm", it.roundToInt()) }
        s.odometer.fresh(eventDriven = true)?.let { od ->
            o.put("odometer_km", od.km.roundToInt())
            od.tripKm?.let { o.put("trip_km", (it * 10).roundToInt() / 10.0) }
        }
        s.fuel.fresh(eventDriven = true)?.let { f ->
            val electric = s.fuelTypes.isNotEmpty() && s.fuelTypes.all { it == 10 } // FUEL_TYPE_ELECTRIC
            f.levelPercent?.let { o.put(if (electric) "battery_percent" else "fuel_percent", it) }
            f.rangeKm?.let { o.put("range_km", it) }
            if (f.low == true) warnings.put(if (electric) "battery low" else "fuel low")
        }
        s.environment.fresh(eventDriven = true)?.let { e ->
            e.temperatureC?.let { o.put("outside_temperature_c", (it * 10).roundToInt() / 10.0) }
            e.pressureKpa?.let { o.put("air_pressure_kpa", it.roundToInt()) }
            e.rain?.let { o.put("raining", it > 0) }
        }
        s.hvac.fresh(eventDriven = true)?.let { h ->
            h.currentC?.let { o.put("cabin_temperature_c", (it * 10).roundToInt() / 10.0) }
            h.targetC?.let { o.put("climate_set_c", (it * 10).roundToInt() / 10.0) }
        }
        s.doors.fresh(eventDriven = true)?.let { d ->
            val open = buildList {
                if (d.hoodOpen == true) add("hood")
                if (d.trunkOpen == true) add("trunk")
                d.doorsOpen.forEachIndexed { i, isOpen -> if (isOpen) add("door ${i + 1}") }
            }
            o.put("open", JSONArray(open))
            if (open.isNotEmpty()) warnings.put("open: " + open.joinToString())
        }
        s.lights.fresh(eventDriven = true)?.let { l ->
            l.headLights?.let { o.put("headlights", it.name.lowercase()) }
            l.turn?.takeIf { it != CarData.Turn.NONE }?.let { o.put("turn_signal", it.name.lowercase()) }
            if (l.hazard == true) o.put("hazard_lights", true)
        }
        s.tirePressuresKpa.fresh(eventDriven = true)?.takeIf { it.isNotEmpty() }?.let { p ->
            o.put("tyre_pressures_kpa", JSONArray(p.map { it.roundToInt() }))
            // No nominal pressure in AAP: flag a tyre well below the fullest one (front and rear
            // differ by a few percent at most), or below what no car tyre should run at. The
            // absolute floor assumes kPa (unit is a guess); skip it if the values look like psi.
            val max = p.max()
            val low = p.indices.filter { p[it] < 0.85f * max || (max > 100f && p[it] < 150f) }
            if (low.isNotEmpty()) warnings.put("low tyre pressure: tyre " + low.joinToString { (it + 1).toString() })
        }
        s.passengerPresent.fresh(eventDriven = true)?.let { o.put("passenger", it) }
        s.tollCardPresent.fresh(eventDriven = true)?.let { o.put("toll_card_inserted", it) }
        s.diagnostics.fresh(eventDriven = true)?.takeIf { it.isNotEmpty() }?.let { warnings.put("the car reports diagnostic trouble codes") }
        if (warnings.length() > 0) o.put("warnings", warnings)
        return o.takeIf { it.length() > 0 }
    }
}
