package net.prototux.aare.obdapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.obd.ObdReader
import net.prototux.aare.obd.ObdReadings
import java.io.File

/**
 * The OBD2 app's state: it takes every round from [ObdReader] (on the reader's thread) and keeps
 * the trip, the gear and the history. The car screen reads it and calls back in
 * (reset the trip). Everything is opt-in: it's attached only with the OBD2 setting on.
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
        }
        ObdReader.roundListener = ObdReader.RoundListener { r, t, sim -> onRound(r, t, sim) }
    }

    fun settings(context: Context): ObdAppSettings = settings ?: ObdAppSettings(context)

    @Synchronized
    private fun onRound(r: ObdReadings?, now: Long, sim: Boolean) {
        val s = settings ?: return
        if (r == null) { endTrip(s); return }
        if (!running) { running = true; simulated = sim; trip.reset() }
        val car = s.car()

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
        _snapshot.value = _snapshot.value.copy(readings = null)
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

    private fun record(e: ObdHistory.Entry) {
        // The pretend adapter's numbers don't become records.
        if (simulated) return
        history?.add(e)
        _historyVersion.value++
    }

    fun clearHistory() { history?.clear(); _historyVersion.value++ }

    private const val MIN_TRIP_KM = 1f
}
