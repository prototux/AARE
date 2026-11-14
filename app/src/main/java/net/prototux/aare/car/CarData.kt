package net.prototux.aare.car

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the car tells us about itself: who it is (service discovery) and the latest value
 * of each sensor, converted from AAP's fixed-point fields to plain units. One car at a time, so
 * this is process-wide; [discovered] starts a new car and [reset] forgets it.
 *
 * Fed from the session's reader thread; [state] and the listeners can be read from any thread.
 * Each value carries the wall-clock time it arrived ([Timed.atMillis]), since cars send some
 * sensors only when they change.
 */
object CarData {

    /** A sensor value and when it arrived (System.currentTimeMillis). */
    data class Timed<T>(val value: T, val atMillis: Long)

    data class Identity(val make: String, val model: String, val year: Int?)

    data class Speed(val metersPerSecond: Float, val cruiseEngaged: Boolean?, /** unit not documented by AAP */ val cruiseSetSpeed: Int?)
    data class Odometer(val km: Float, val tripKm: Float?)
    /** [rangeKm]: AAP's "range" has no unit suffix; the old car support library documents km. */
    data class Fuel(val levelPercent: Int?, val rangeKm: Int?, val low: Boolean?)
    data class Compass(val bearingDeg: Float, val pitchDeg: Float?, val rollDeg: Float?)
    data class Environment(val temperatureC: Float?, val pressureKpa: Float?, /** 0 = dry, higher = heavier rain */ val rain: Int?)
    data class Hvac(val targetC: Float?, val currentC: Float?)
    data class DeadReckoning(val steeringAngleDeg: Float?, val wheelSpeedsMps: List<Float>)
    data class Doors(val hoodOpen: Boolean?, val trunkOpen: Boolean?, val doorsOpen: List<Boolean>) {
        val anyOpen get() = hoodOpen == true || trunkOpen == true || doorsOpen.any { it }
    }
    enum class HeadLights { OFF, ON, HIGH }
    enum class Turn { NONE, LEFT, RIGHT }
    data class Lights(val headLights: HeadLights?, val turn: Turn?, val hazard: Boolean?)
    data class Vector3(val x: Float?, val y: Float?, val z: Float?)
    data class Satellites(val inUse: Int, val inView: Int?)

    data class State(
        /** False until a car is connected; values below are then all null. */
        val connected: Boolean = false,
        val identity: Identity? = null,
        /** Sensors the car advertises (not all of them ever report). */
        val advertised: Set<SensorType> = emptySet(),
        /** AAP FuelType / EvConnectorType numbers (same numbering as the Car App Library's EnergyProfile). */
        val fuelTypes: List<Int> = emptyList(),
        val evConnectorTypes: List<Int> = emptyList(),

        val drivingStatus: Timed<Int>? = null,
        val nightMode: Timed<Boolean>? = null,
        val speed: Timed<Speed>? = null,
        val gear: Timed<Int>? = null,
        val parkingBrake: Timed<Boolean>? = null,
        val compass: Timed<Compass>? = null,
        val rpm: Timed<Float>? = null,
        val odometer: Timed<Odometer>? = null,
        val fuel: Timed<Fuel>? = null,
        val environment: Timed<Environment>? = null,
        val hvac: Timed<Hvac>? = null,
        val deadReckoning: Timed<DeadReckoning>? = null,
        val passengerPresent: Timed<Boolean>? = null,
        val doors: Timed<Doors>? = null,
        val lights: Timed<Lights>? = null,
        /** One per tyre, in kPa (AAP sends tire_pressures_e2; kPa is our reading of the unit). */
        val tirePressuresKpa: Timed<List<Float>>? = null,
        /** m/s² */
        val accelerometer: Timed<Vector3>? = null,
        /** rad/s */
        val gyroscope: Timed<Vector3>? = null,
        val satellites: Timed<Satellites>? = null,
        val tollCardPresent: Timed<Boolean>? = null,
        /** Raw OBD-II trouble codes, as the car sends them. */
        val diagnostics: Timed<ByteArray>? = null,
    ) {
        fun has(type: SensorType) = type in advertised

        /** GEAR_PARK is 101, GEAR_REVERSE 102, GEAR_DRIVE 100, numbers are manual gears. */
        val gearName: String? get() = gear?.value?.let {
            when (it) { 0 -> "neutral"; 100 -> "drive"; 101 -> "park"; 102 -> "reverse"; else -> "gear $it" }
        }
    }

    /** Which part of [State] a batch touched, so listeners only refresh what changed. */
    enum class Kind {
        LOCATION, COMPASS, SPEED, RPM, ODOMETER, FUEL, PARKING_BRAKE, GEAR, DIAGNOSTICS, NIGHT_MODE, ENVIRONMENT, HVAC,
        DRIVING_STATUS, DEAD_RECKONING, PASSENGER, DOORS, LIGHTS, TIRE_PRESSURE, ACCELEROMETER, GYROSCOPE, GPS_SATELLITES, TOLL_CARD,
    }

    fun interface Listener {
        /** Called on the session's reader thread: post elsewhere for anything slow. */
        fun onCarData(state: State, changed: Set<Kind>)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()
    /** Whoever last called [discovered]: a quick reconnect's teardown must not [reset] a car the new session already found. */
    private var owner: Any? = null

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /**
     * A new car: its identity and the sensors and fuels it advertises. Forgets the previous car's
     * values. [forceElectric]: see [fuelTypes].
     */
    fun discovered(response: ServiceDiscoveryResponse, owner: Any? = null, forceElectric: Boolean = false) {
        val hu = response.headunitInfo
        val sensors = response.channelsList.firstOrNull { it.hasSensorSourceService() }?.sensorSourceService
        synchronized(lock) {
            this.owner = owner
            _state.value = State(
                connected = true,
                identity = Identity(hu.make.ifEmpty { response.make }, hu.model.ifEmpty { response.model },
                    hu.year.ifEmpty { response.year }.trim().toIntOrNull()),
                // sensor_type is optional: an unknown type parses as unset and would read as the default (location).
                advertised = sensors?.sensorsList?.filter { it.hasSensorType() }?.map { it.sensorType }?.toSet().orEmpty(),
                fuelTypes = fuelTypes(sensors?.supportedFuelTypesList?.map { it.number }.orEmpty(), forceElectric),
                evConnectorTypes = sensors?.supportedEvConnectorTypesList?.map { it.number }.orEmpty(),
            )
        }
    }

    /** AAP's FUEL_TYPE_UNKNOWN and FUEL_TYPE_ELECTRIC. */
    private const val FUEL_UNKNOWN = 0
    private const val FUEL_ELECTRIC = 10

    /**
     * The fuel types to tell apps. Some electric cars report none, or an unknown one (vehicles.json
     * `forceElectric`): those are made electric, so EV charging apps work.
     */
    fun fuelTypes(reported: List<Int>, forceElectric: Boolean): List<Int> =
        if (forceElectric && (reported.isEmpty() || FUEL_UNKNOWN in reported)) listOf(FUEL_ELECTRIC) else reported

    /** The car is gone, but only if [owner] still owns it: a session tearing down after a new one already reconnected must not wipe its data. */
    fun reset(owner: Any? = null) {
        synchronized(lock) {
            if (owner != null && this.owner !== owner) return
            this.owner = null
            _state.value = State()
        }
    }

    /** Folds a sensor batch into [state] and tells the listeners what changed. Returns the changed kinds. */
    fun update(batch: SensorBatch): Set<Kind> {
        val now = System.currentTimeMillis()
        val changed = HashSet<Kind>()
        val next = synchronized(lock) {
            var s = _state.value
            // A batch may hold several samples of a sensor: the last one is the newest.
            if (batch.locationDataCount > 0) changed += Kind.LOCATION // the Location itself lives in CarRuntime
            batch.compassDataList.lastOrNull()?.let { c ->
                s = s.copy(compass = Timed(Compass(c.bearingE6 / 1e6f, if (c.hasPitchE6()) c.pitchE6 / 1e6f else null,
                    if (c.hasRollE6()) c.rollE6 / 1e6f else null), now)); changed += Kind.COMPASS
            }
            batch.speedDataList.lastOrNull()?.let { v ->
                s = s.copy(speed = Timed(Speed(v.speedE3 / 1e3f, if (v.hasCruiseEngaged()) v.cruiseEngaged else null,
                    if (v.hasCruiseSetSpeed()) v.cruiseSetSpeed else null), now)); changed += Kind.SPEED
            }
            batch.rpmDataList.lastOrNull()?.let { s = s.copy(rpm = Timed(it.rpmE3 / 1e3f, now)); changed += Kind.RPM }
            batch.odometerDataList.lastOrNull()?.let { o ->
                s = s.copy(odometer = Timed(Odometer(o.kmsE1 / 10f, if (o.hasTripKmsE1()) o.tripKmsE1 / 10f else null), now)); changed += Kind.ODOMETER
            }
            batch.fuelDataList.lastOrNull()?.let { f ->
                s = s.copy(fuel = Timed(Fuel(if (f.hasFuelLevel()) f.fuelLevel else null, if (f.hasRange()) f.range else null,
                    if (f.hasLowFuelWarning()) f.lowFuelWarning else null), now)); changed += Kind.FUEL
            }
            batch.parkingBrakeDataList.lastOrNull()?.let { s = s.copy(parkingBrake = Timed(it.parkingBrake, now)); changed += Kind.PARKING_BRAKE }
            batch.gearDataList.lastOrNull()?.let { s = s.copy(gear = Timed(it.gear.number, now)); changed += Kind.GEAR }
            batch.diagnosticsDataList.lastOrNull()?.let { s = s.copy(diagnostics = Timed(it.dtc.toByteArray(), now)); changed += Kind.DIAGNOSTICS }
            batch.nightModeDataList.lastOrNull()?.let { s = s.copy(nightMode = Timed(it.nightMode, now)); changed += Kind.NIGHT_MODE }
            batch.environmentDataList.lastOrNull()?.let { e ->
                s = s.copy(environment = Timed(Environment(if (e.hasTemperatureE3()) e.temperatureE3 / 1e3f else null,
                    if (e.hasPressureE3()) e.pressureE3 / 1e3f else null, if (e.hasRain()) e.rain else null), now)); changed += Kind.ENVIRONMENT
            }
            batch.hvacDataList.lastOrNull()?.let { h ->
                s = s.copy(hvac = Timed(Hvac(if (h.hasTargetTemperatureE3()) h.targetTemperatureE3 / 1e3f else null,
                    if (h.hasCurrentTemperatureE3()) h.currentTemperatureE3 / 1e3f else null), now)); changed += Kind.HVAC
            }
            batch.drivingStatusDataList.lastOrNull()?.let { s = s.copy(drivingStatus = Timed(it.status, now)); changed += Kind.DRIVING_STATUS }
            batch.deadReckoningDataList.lastOrNull()?.let { d ->
                s = s.copy(deadReckoning = Timed(DeadReckoning(if (d.hasSteeringAngleE1()) d.steeringAngleE1 / 10f else null,
                    d.wheelSpeedE3List.map { it / 1e3f }), now)); changed += Kind.DEAD_RECKONING
            }
            batch.passengerDataList.lastOrNull()?.let { p ->
                if (p.hasPassengerPresent()) { s = s.copy(passengerPresent = Timed(p.passengerPresent, now)); changed += Kind.PASSENGER }
            }
            batch.doorDataList.lastOrNull()?.let { d ->
                s = s.copy(doors = Timed(Doors(if (d.hasHoodOpen()) d.hoodOpen else null, if (d.hasTrunkOpen()) d.trunkOpen else null,
                    d.doorOpenList.toList()), now)); changed += Kind.DOORS
            }
            batch.lightDataList.lastOrNull()?.let { l ->
                val head = if (l.hasHeadLightState()) when (l.headLightState.number) { 2 -> HeadLights.ON; 3 -> HeadLights.HIGH; else -> HeadLights.OFF } else null
                val turn = if (l.hasTurnIndicatorState()) when (l.turnIndicatorState.number) { 2 -> Turn.LEFT; 3 -> Turn.RIGHT; else -> Turn.NONE } else null
                s = s.copy(lights = Timed(Lights(head, turn, if (l.hasHazardLightsOn()) l.hazardLightsOn else null), now)); changed += Kind.LIGHTS
            }
            batch.tirePressureDataList.lastOrNull()?.let { t ->
                s = s.copy(tirePressuresKpa = Timed(t.tirePressuresE2List.map { it / 1e2f }, now)); changed += Kind.TIRE_PRESSURE
            }
            batch.accelerometerDataList.lastOrNull()?.let { a ->
                s = s.copy(accelerometer = Timed(Vector3(if (a.hasAccelerationXE3()) a.accelerationXE3 / 1e3f else null,
                    if (a.hasAccelerationYE3()) a.accelerationYE3 / 1e3f else null,
                    if (a.hasAccelerationZE3()) a.accelerationZE3 / 1e3f else null), now)); changed += Kind.ACCELEROMETER
            }
            batch.gyroscopeDataList.lastOrNull()?.let { g ->
                s = s.copy(gyroscope = Timed(Vector3(if (g.hasRotationSpeedXE3()) g.rotationSpeedXE3 / 1e3f else null,
                    if (g.hasRotationSpeedYE3()) g.rotationSpeedYE3 / 1e3f else null,
                    if (g.hasRotationSpeedZE3()) g.rotationSpeedZE3 / 1e3f else null), now)); changed += Kind.GYROSCOPE
            }
            batch.gpsSatelliteDataList.lastOrNull()?.let { g ->
                s = s.copy(satellites = Timed(Satellites(g.numberInUse, if (g.hasNumberInView()) g.numberInView else null), now)); changed += Kind.GPS_SATELLITES
            }
            batch.tollCardDataList.lastOrNull()?.let { s = s.copy(tollCardPresent = Timed(it.isCardPresent, now)); changed += Kind.TOLL_CARD }
            _state.value = s
            s
        }
        if (changed.isNotEmpty()) for (l in listeners) runCatching { l.onCarData(next, changed) }
        return changed
    }
}
