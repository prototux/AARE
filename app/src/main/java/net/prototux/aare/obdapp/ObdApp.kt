package net.prototux.aare.obdapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.obd.ObdReader
import net.prototux.aare.obd.ObdReadings
import net.prototux.aare.obdapp.track.Dyno
import net.prototux.aare.obdapp.track.GForce
import net.prototux.aare.obdapp.track.LapTimer
import net.prototux.aare.obdapp.track.SpeedTests
import java.io.File

/**
 * The OBD2 app's state: it takes every round from [ObdReader] (on the reader's thread) and keeps
 * the trip, the gear, the track timers and the history. The car screen reads it and calls back in
 * (arm a run, reset the trip). Everything is opt-in: it's attached only with the OBD2 setting on.
 *
 * A trip is one car session: it's saved to the history when the session ends (or on "Reset"), and
 * the next session starts a new one.
 */
object ObdApp {
    /** What the screen shows, refreshed at most [SCREEN_MS]. Values that need no history come straight from the readings. */
    data class Snapshot(
        val readings: ObdReadings? = null,
        val simulated: Boolean = false,
        val gear: Int? = null,
        val kmPerLiter: Float? = null,
        val tripKm: Float = 0f,
        val tripMinutes: Float = 0f,
        val tripAverage: Float? = null,
        val rangeKm: Float? = null,
        /** The second gauge: boost in bar (turbo) or load in % (aspirated). */
        val showsBoost: Boolean = false,
        val gauge: Float? = null,
        val gaugePeak: Float? = null,
        val timeMs: Long = 0L,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    /** Bumped whenever the history changes, for the History page. */
    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion

    private var settings: ObdAppSettings? = null
    var history: ObdHistory? = null; private set

    val trip = Trip()
    private var gears = GearLearner()
    val tests = SpeedTests()
    val laps = LapTimer()
    val dyno = Dyno()
    val g = GForce()

    private var simulated = false
    private var running = false
    private var shownMs = 0L
    private var gearsSavedMs = 0L

    private const val SCREEN_MS = 100L
    private const val GEARS_SAVE_MS = 60_000L
    private const val TURBO_KPA = 15f

    /** Hooks into the reader (CarSession, with the OBD2 setting on). Safe to call again. */
    @Synchronized
    fun attach(context: Context) {
        if (settings == null) {
            val s = ObdAppSettings(context)
            settings = s
            history = ObdHistory(File(context.applicationContext.filesDir, "obd_history.tsv"))
            gears = GearLearner(s.gears)
            tests.all.forEach { t -> t.onResult = { kind, v, second -> record(ObdHistory.Entry(System.currentTimeMillis(), kind, v, second)) } }
            laps.onLap = { s2 -> record(ObdHistory.Entry(System.currentTimeMillis(), ObdHistory.Kind.LAP, s2)) }
            dyno.onResult = { hp, rpm -> record(ObdHistory.Entry(System.currentTimeMillis(), ObdHistory.Kind.DYNO, hp, rpm)) }
        }
        ObdReader.roundListener = ObdReader.RoundListener { r, t, sim -> onRound(r, t, sim) }
    }

    fun settings(context: Context): ObdAppSettings = settings ?: ObdAppSettings(context)

    /**
     * Track, Lap and Dyno are parked only, like the car screen's other parked-only limits:
     * passenger mode lifts it (CarRuntime keeps this up to date). Driving off disarms them.
     */
    @Volatile var trackAllowed = true

    @Synchronized
    private fun onRound(r: ObdReadings?, now: Long, sim: Boolean) {
        val s = settings ?: return
        if (r == null) { endTrip(s); return }
        if (!trackAllowed) disarmTrack()
        if (!running) { running = true; simulated = sim; trip.reset() }
        val car = s.car()
        dyno.massKg = car.massKg.toFloat()

        // AUTO: a manifold clearly above the outside air means a turbo (kept for next time).
        if (s.engine == ObdAppSettings.Engine.AUTO && !s.turboSeen) {
            val m = r.manifoldKpa; val b = r.barometricKpa ?: 100f
            if (m != null && m > b + TURBO_KPA) s.turboSeen = true
        }
        val boost = s.showsBoost
        val gaugeValue = if (boost) r.boost else r.load
        trip.feed(r, car, gaugeValue, now)
        val gear = gears.feed(r.speed, r.rpm)
        if (now - gearsSavedMs > GEARS_SAVE_MS) { gearsSavedMs = now; s.gears = gears.save() }
        tests.feed(r.speed, now)
        dyno.feed(r.speed, r.rpm, now)
        g.speed(r.speed, now)

        if (now - shownMs >= SCREEN_MS) {
            shownMs = now
            _snapshot.value = Snapshot(
                readings = r, simulated = sim, gear = gear,
                kmPerLiter = FuelMath.kmPerLiter(r, car),
                tripKm = trip.km, tripMinutes = trip.minutes, tripAverage = trip.average,
                rangeKm = trip.rangeKm(r.fuel, car.tankLiters, s.lastAverage),
                showsBoost = boost, gauge = gaugeValue, gaugePeak = trip.peak, timeMs = now,
            )
        }
    }

    /** The session ended (or "Reset"): the trip goes to the history and its average is kept for the range. */
    private fun endTrip(s: ObdAppSettings) {
        if (!running) return
        running = false
        s.gears = gears.save()
        trip.average?.let { s.lastAverage = it }
        if (!simulated && trip.km >= MIN_TRIP_KM) record(ObdHistory.Entry(System.currentTimeMillis(), ObdHistory.Kind.TRIP, trip.km, trip.average ?: 0f))
        disarmTrack()
        _snapshot.value = _snapshot.value.copy(readings = null)
    }

    private fun disarmTrack() {
        if (tests.all.any { it.armed }) tests.disarmAll()
        if (laps.state != LapTimer.State.OFF) laps.disarm()
        if (dyno.armed) dyno.toggle()
    }

    @Synchronized
    fun resetTrip() {
        val s = settings ?: return
        trip.average?.let { s.lastAverage = it }
        if (!simulated && trip.km >= MIN_TRIP_KM) record(ObdHistory.Entry(System.currentTimeMillis(), ObdHistory.Kind.TRIP, trip.km, trip.average ?: 0f))
        trip.reset()
    }

    @Synchronized
    fun forgetGears() { gears.reset(); settings?.gears = "" }

    val learnedGears: Int @Synchronized get() = gears.gears.size

    /** The phone's accelerometer, while the track page is open. */
    @Synchronized
    fun accelerometer(x: Float, y: Float, z: Float, ms: Long) = g.sample(x, y, z, ms)

    /** A GPS point (the car's, or the phone's), while the lap page is open. */
    @Synchronized
    fun location(lat: Double, lon: Double, ms: Long, kmh: Float) = laps.point(lat, lon, ms, kmh)

    /** Runs a change to the timers from the screen, in step with the reader. */
    @Synchronized
    fun <T> edit(block: ObdApp.() -> T): T = block()

    private fun record(e: ObdHistory.Entry) {
        // The pretend adapter's numbers don't become records.
        if (simulated) return
        history?.add(e)
        _historyVersion.value++
    }

    fun clearHistory() { history?.clear(); _historyVersion.value++ }

    private const val MIN_TRIP_KM = 1f
}
