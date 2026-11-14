package net.prototux.aare.host

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import android.location.Location
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import net.prototux.aare.car.CarData

/**
 * The Car App Library's CarInfo and CarSensors results, built from [CarData].
 *
 * The library's contract: a result type we answer with isSupported = false becomes its own
 * "unimplemented" value in the app. So a whole result is unsupported when the car doesn't
 * advertise the sensor behind it; when it does but hasn't reported yet, we answer with
 * STATUS_UNAVAILABLE fields; fields AAP has no source for at all are STATUS_UNIMPLEMENTED.
 */
object CarHardwareResults {
    // ICarHardwareResultTypes (car-app-projected)
    const val MODEL = 1
    const val ENERGY_PROFILE = 2
    const val TOLL = 3
    const val ENERGY_LEVEL = 4
    const val SPEED = 5
    const val MILEAGE = 6
    const val EV_STATUS = 7
    const val ACCELEROMETER = 20
    const val COMPASS = 21
    const val GYROSCOPE = 22
    const val CAR_LOCATION = 23

    /** The car sensors behind each result type, to ask the car only for what apps subscribed to. */
    fun sensorsFor(types: Collection<Int>): Set<SensorType> = buildSet {
        for (t in types) when (t) {
            SPEED -> add(SensorType.SENSOR_SPEED)
            MILEAGE -> add(SensorType.SENSOR_ODOMETER)
            ENERGY_LEVEL -> { add(SensorType.SENSOR_FUEL); add(SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA) }
            TOLL -> add(SensorType.SENSOR_TOLL_CARD)
            ACCELEROMETER -> add(SensorType.SENSOR_ACCELEROMETER_DATA)
            GYROSCOPE -> add(SensorType.SENSOR_GYROSCOPE_DATA)
            COMPASS -> add(SensorType.SENSOR_COMPASS)
            CAR_LOCATION -> add(SensorType.SENSOR_LOCATION)
            // MODEL and ENERGY_PROFILE come from service discovery; EV_STATUS has no source.
            else -> Unit
        }
    }

    /** Result types that depend on each kind of sensor data, to push only what changed. */
    fun typesFor(changed: Set<CarData.Kind>): Set<Int> = buildSet {
        for (k in changed) when (k) {
            CarData.Kind.SPEED -> add(SPEED)
            CarData.Kind.ODOMETER -> add(MILEAGE)
            CarData.Kind.FUEL, CarData.Kind.ENERGY_MODEL -> add(ENERGY_LEVEL)
            CarData.Kind.TOLL_CARD -> add(TOLL)
            CarData.Kind.ACCELEROMETER -> add(ACCELEROMETER)
            CarData.Kind.GYROSCOPE -> add(GYROSCOPE)
            CarData.Kind.COMPASS -> add(COMPASS)
            CarData.Kind.LOCATION -> add(CAR_LOCATION)
            else -> Unit
        }
    }

    /**
     * The result for [type], or null for "not supported". [location] and [heading] are the
     * runtime's car GPS and compass (null when the car hasn't sent them, as before: apps such as
     * CoMaps then keep the phone's own sensors).
     */
    fun build(type: Int, s: CarData.State, location: Location?, heading: Float?): Any? {
        if (!s.connected && type != CAR_LOCATION && type != COMPASS) return null
        return when (type) {
            MODEL -> s.identity?.let { id ->
                Model.Builder()
                    .setManufacturer(text(id.make))
                    .setName(text(id.model))
                    .setYear(id.year?.let { CarValue(it, now(), CarValue.STATUS_SUCCESS) } ?: unavailable())
                    .build()
            }
            ENERGY_PROFILE -> if (!s.has(SensorType.SENSOR_FUEL) && s.fuelTypes.isEmpty()) null else EnergyProfile.Builder()
                .setFuelTypes(intList(s.fuelTypes))
                .setEvConnectorTypes(intList(s.evConnectorTypes))
                .build()
            ENERGY_LEVEL -> if (!s.has(SensorType.SENSOR_FUEL) && !s.has(SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA)) null else energyLevel(s)
            SPEED -> if (!s.has(SensorType.SENSOR_SPEED)) null else Speed.Builder()
                .setRawSpeedMetersPerSecond(s.speed?.let { CarValue(it.value.metersPerSecond, it.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
                // The speed the dashboard shows (usually a little above the real one) and its unit aren't in AAP.
                .setDisplaySpeedMetersPerSecond(unimplemented())
                .setSpeedDisplayUnit(unimplemented())
                .build()
            MILEAGE -> if (!s.has(SensorType.SENSOR_ODOMETER)) null else Mileage.Builder()
                .setOdometerMeters(s.odometer?.let { CarValue(it.value.km * 1000f, it.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
                .setDistanceDisplayUnit(unimplemented())
                .build()
            TOLL -> if (!s.has(SensorType.SENSOR_TOLL_CARD)) null else TollCard.Builder()
                // AAP only says whether a card is inserted, not whether it is valid: present counts as valid.
                .setCardState(s.tollCardPresent?.let {
                    CarValue(if (it.value) TollCard.TOLLCARD_STATE_VALID else TollCard.TOLLCARD_STATE_NOT_INSERTED, it.atMillis, CarValue.STATUS_SUCCESS)
                } ?: unavailable())
                .build()
            // AAP has no charge-port data.
            EV_STATUS -> null
            ACCELEROMETER -> if (!s.has(SensorType.SENSOR_ACCELEROMETER_DATA)) null else Accelerometer(vector(s.accelerometer))
            GYROSCOPE -> if (!s.has(SensorType.SENSOR_GYROSCOPE_DATA)) null else Gyroscope(vector(s.gyroscope))
            COMPASS -> heading?.let { h ->
                // [azimuth, pitch, roll] in degrees; pitch and roll only when the car gives both.
                val c = s.compass?.value
                val values = if (c?.pitchDeg != null && c.rollDeg != null) listOf(h, c.pitchDeg, c.rollDeg) else listOf(h)
                Compass(CarValue(values, s.compass?.atMillis ?: now(), CarValue.STATUS_SUCCESS))
            }
            CAR_LOCATION -> location?.let { CarHardwareLocation(CarValue(it, now(), CarValue.STATUS_SUCCESS)) }
            else -> null
        }
    }

    private fun energyLevel(s: CarData.State): EnergyLevel {
        val f = s.fuel
        // An electric-only car reports its battery as "fuel".
        val electric = s.fuelTypes.isNotEmpty() && s.fuelTypes.all { it == EnergyProfile.FUEL_TYPE_ELECTRIC }
        val level: CarValue<Float> = f?.value?.levelPercent?.let { CarValue(it.toFloat(), f.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable()
        // The energy model's charge (stored / capacity) when the car sends one: a hybrid's battery,
        // or an electric car that leaves the fuel level out.
        val model: CarValue<Float>? = s.energyModel?.let { e -> e.value.batteryPercent?.let { CarValue(it, e.atMillis, CarValue.STATUS_SUCCESS) } }
        val battery: CarValue<Float> = when {
            electric && level.status == CarValue.STATUS_SUCCESS -> level
            model != null -> model
            electric || s.has(SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA) -> unavailable()
            else -> unimplemented()
        }
        return EnergyLevel.Builder()
            .setFuelPercent(if (electric) unimplemented() else level)
            .setBatteryPercent(battery)
            .setEnergyIsLow(f?.value?.low?.let { CarValue(it, f.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
            .setRangeRemainingMeters(f?.value?.rangeKm?.let { CarValue(it * 1000f, f.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable())
            .setDistanceDisplayUnit(unimplemented())
            .setFuelVolumeDisplayUnit(unimplemented())
            .build()
    }

    /** [x, y, z]; an axis the car leaves out counts as 0. */
    private fun vector(v: CarData.Timed<CarData.Vector3>?): CarValue<List<Float>> =
        v?.let { CarValue(listOf(it.value.x ?: 0f, it.value.y ?: 0f, it.value.z ?: 0f), it.atMillis, CarValue.STATUS_SUCCESS) } ?: unavailable()

    private fun text(value: String): CarValue<String> =
        if (value.isNotBlank()) CarValue(value, now(), CarValue.STATUS_SUCCESS) else unavailable()

    private fun intList(values: List<Int>): CarValue<List<Int>> =
        if (values.isNotEmpty()) CarValue(values, now(), CarValue.STATUS_SUCCESS) else CarValue(emptyList(), now(), CarValue.STATUS_UNAVAILABLE)

    private fun <T> unavailable(): CarValue<T> = CarValue(null, now(), CarValue.STATUS_UNAVAILABLE)
    private fun <T> unimplemented(): CarValue<T> = CarValue(null, 0, CarValue.STATUS_UNIMPLEMENTED)
    private fun now() = System.currentTimeMillis()
}
