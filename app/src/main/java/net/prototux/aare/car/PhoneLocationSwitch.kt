package net.prototux.aare.car

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import net.prototux.aare.launch.AppLauncher

/**
 * Turns the phone's location on when a car connects, as Android Auto asks the driver to, and off
 * when it goes, each if chosen: apps (Blitzer.de, Maps) need it, and the car's GPS only reaches them
 * through it. Needs Shizuku or root; without them it does nothing.
 */
class PhoneLocationSwitch(private val context: Context) {
    private var started = false

    /**
     * The session runs; [on]: turn the location on. Also on when the last session turned it off a
     * moment ago: a short drop mid-drive would otherwise leave navigation without a position.
     */
    fun start(on: Boolean) { started = true; switch(true) { turnsOn(on, offAt, SystemClock.elapsedRealtime()) } }

    /** Only after [start]: a session that ended before it ran (a failed connect) leaves the location as it is. */
    fun stop() { if (started) switch(false) }

    /** One after the other, each checking when it runs: an off, then an on right after (a quick reconnect) ends on. */
    private fun switch(on: Boolean, wanted: () -> Boolean = { true }) {
        if (!AppLauncher.available(context)) return
        worker.execute {
            // Decided here, after the last session's off ran.
            if (!wanted()) return@execute
            val lm = context.getSystemService(LocationManager::class.java) ?: return@execute
            if (lm.isLocationEnabled != on && set(on)) offAt = if (on) 0L else SystemClock.elapsedRealtime()
        }
    }

    private fun set(on: Boolean): Boolean {
        val launcher = AppLauncher(context, "location")
        return try {
            launcher.connect() && launcher.setLocationEnabled(on)
        } finally {
            launcher.release()
        }.also { Log.i(TAG, "phone location ${if (on) "on" else "off"}: $it") }
    }

    private companion object {
        const val TAG = "AareLocation"

        /** Shared by every session's switch, so the last session's off and the next one's on keep their order. */
        val worker = Executors.newSingleThreadExecutor()

        /** When the last switch turned the location off (elapsed realtime), 0 if it didn't (worker thread). */
        var offAt = 0L
    }
}

/** How soon after a session turned the location off the next one counts as a reconnect. */
internal const val RECONNECT_MS = 120_000L

/** Whether a session starting at [now] turns the location on: chosen ([on]), or it went off with the last session at [offAt] a moment ago. */
internal fun turnsOn(on: Boolean, offAt: Long, now: Long): Boolean = on || (offAt > 0 && now - offAt < RECONNECT_MS)
