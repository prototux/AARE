package net.prototux.aare.obd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import java.io.IOException

/**
 * Reads an OBD2 adapter continuously while a car is connected (opt-in, Settings.obdAdapter), for
 * the Car data viewers: engine data many head units don't send over AAP (revs, coolant, battery…).
 *
 * An adapter takes one connection at a time: it's held only between [start] and [stop] (the car
 * session), so other OBD apps can use it the rest of the time. With no adapter around (left at
 * home, unpaired, off) it tries [ATTEMPTS] times, then once every [SLOW_RETRY_MS], so an adapter
 * switched on later in the drive is still found.
 */
object ObdReader {
    enum class State { OFF, SEARCHING, CONNECTED, NO_CAR, GAVE_UP }

    data class Status(val state: State, val adapter: String = "", val detail: String = "")

    private val _readings = MutableStateFlow<ObdReadings?>(null)
    /** The latest readings; null when not reading. */
    val readings: StateFlow<ObdReadings?> = _readings

    private val _status = MutableStateFlow(Status(State.OFF))
    val status: StateFlow<Status> = _status

    private const val TAG = "AareObd"
    private const val ATTEMPTS = 4
    private const val RETRY_MS = 8_000L
    private const val NO_CAR_RETRY_MS = 15_000L
    /** After [ATTEMPTS] misses, one more try this often (a Bluetooth connection attempt costs little). */
    private const val SLOW_RETRY_MS = 60_000L
    /** Never spin without a pause, even when the adapter answers instantly. */
    private const val MIN_ROUND_MS = 50L
    /** At most this often to the screen (10 a second). */
    private const val SCREEN_MS = 100L

    @Volatile private var generation = 0
    @Volatile private var link: ObdLink? = null
    private var thread: Thread? = null
    /** The session that started the reader: a quick reconnect's teardown mustn't stop the new one's. */
    private var owner: Any? = null

    /** Starts reading for [owner] (the pretend adapter when [simulated], debug builds); a running reader restarts. */
    @Synchronized
    fun start(context: Context, simulated: Boolean, owner: Any? = null) {
        stop()
        this.owner = owner
        val app = context.applicationContext
        if (!simulated && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status(State.GAVE_UP, detail = "Bluetooth permission missing")
            return
        }
        val g = ++generation
        thread = Thread({ run(app, simulated, g) }, "aare-obd").apply { isDaemon = true; start() }
    }

    /** Stops and lets go of the adapter, unless a newer session than [owner] started it again. */
    @Synchronized
    fun stop(owner: Any? = null) {
        if (owner != null && this.owner !== owner) return
        this.owner = null
        generation++
        link?.close()
        link = null
        thread?.interrupt()
        thread = null
        _readings.value = null
        _status.value = Status(State.OFF)
    }

    private fun alive(g: Int) = g == generation

    private fun run(context: Context, simulated: Boolean, g: Int) {
        var failures = 0
        while (alive(g)) {
            if (failures < ATTEMPTS) status(State.SEARCHING, detail = "${failures + 1}/$ATTEMPTS", g = g)
            val x = if (simulated) FakeObdLink() else connect(context)
            if (x == null) {
                if (++failures == ATTEMPTS) log("OBD2: no adapter answered after $ATTEMPTS attempts, trying again every ${SLOW_RETRY_MS / 1000} s")
                if (failures >= ATTEMPTS) status(State.GAVE_UP, g = g)
                sleep(if (failures >= ATTEMPTS) SLOW_RETRY_MS else RETRY_MS)
                continue
            }
            // Stopped while it connected (the car left): let go at once, or the next session's
            // reader, which gets one connection at a time, finds the adapter busy.
            if (!alive(g)) { x.close(); break }
            if (failures >= ATTEMPTS) log("OBD2: adapter found on a later try")
            failures = 0
            link = x
            try {
                val supported = Elm327.prepare(x)
                if (Elm327.READ.none { it in supported }) {
                    // The adapter is on but the car is silent (ignition off?): ask again later.
                    status(State.NO_CAR, x.name, g = g)
                    x.close(); link = null
                    sleep(NO_CAR_RETRY_MS)
                    continue
                }
                val fast = Elm327.engineOnly(x)
                log("OBD2: ${x.name} connected, ${supported.size} PIDs${if (fast) ", engine module only" else ", all modules"}")
                status(State.CONNECTED, x.name, g = g)
                val poller = ObdPoller(x, supported)
                var shown = 0L
                while (alive(g)) {
                    val start = System.currentTimeMillis()
                    val r = poller.readRound()
                    val now = System.currentTimeMillis()
                    (MIN_ROUND_MS - (now - start)).takeIf { it > 0 }?.let(::sleep)
                    if (now - shown >= SCREEN_MS && alive(g)) { shown = now; _readings.value = r }
                }
            } catch (e: IOException) {
                // Closed by stop() is not an error.
                if (alive(g)) { log("OBD2: reading dropped: ${e.message}"); status(State.SEARCHING, x.name, g = g) }
            } finally {
                x.close()
                if (link === x) link = null
            }
            if (alive(g)) sleep(5_000)
        }
    }

    private fun connect(context: Context): ObdLink? {
        if (!BluetoothObdLink.bluetoothOn(context)) return null
        for (device in BluetoothObdLink.pairedAdapters(context, Settings(context).obdAdapterAddress)) {
            try {
                return BluetoothObdLink(device)
            } catch (e: IOException) {
                Log.i(TAG, "can't connect to an adapter: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth refused", e)
                return null
            }
        }
        return null
    }

    private fun status(state: State, adapter: String = "", detail: String = "", g: Int = generation) {
        if (alive(g)) _status.value = Status(state, adapter, detail)
    }

    private fun log(text: String) {
        Log.i(TAG, text)
        DiagLog.add(text)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }
}
