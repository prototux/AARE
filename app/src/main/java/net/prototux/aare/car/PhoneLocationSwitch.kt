package net.prototux.aare.car

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import net.prototux.aare.launch.AppLauncher

/**
 * Turns the phone's location on when a car connects, as Android Auto asks the driver to, and off
 * when it goes if AARE turned it on, each if chosen: apps (Blitzer.de, Maps) need it, and the car's GPS only reaches them
 * through it. Needs Shizuku or root; without them it does nothing.
 */
class PhoneLocationSwitch(private val context: Context) {
    private var started = false

    /**
     * The session runs; [on]: turn the location on. Also on when the last session turned it off a
     * moment ago: a short drop mid-drive would otherwise leave navigation without a position.
     */
    fun start(on: Boolean) { started = true; switch(true) { turnsOn(on, offAt, SystemClock.elapsedRealtime()) } }

    /**
     * Only after [start]: a session that ended before it ran (a failed connect) leaves the location
     * as it is. Off only if AARE turned it on: one the driver had on stays on.
     */
    fun stop() { if (started) switch(false) }

    /** One after the other, each checking when it runs: an off, then an on right after (a quick reconnect) ends on. */
    private fun switch(on: Boolean, wanted: () -> Boolean = { true }) {
        if (!AppLauncher.available(context)) return
        worker.execute {
            // Decided here, after the last session's off ran.
            if (!wanted()) return@execute
            val lm = context.getSystemService(LocationManager::class.java) ?: return@execute
            if (!locationSwitches(on, lm.isLocationEnabled, ours) || !set(on)) return@execute
            ours = on
            offAt = if (on) 0L else SystemClock.elapsedRealtime()
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

        /** Whether AARE turned the location on and it's still on its account (worker thread). */
        var ours = false
    }
}

/** How soon after a session turned the location off the next one counts as a reconnect. */
internal const val RECONNECT_MS = 120_000L

/** Whether a session starting at [now] turns the location on: chosen ([on]), or it went off with the last session at [offAt] a moment ago. */
internal fun turnsOn(on: Boolean, offAt: Long, now: Long): Boolean = on || (offAt > 0 && now - offAt < RECONNECT_MS)

/**
 * Whether the switch changes the location: [on] turns it on when it's off; off turns it off only
 * when it's on and AARE ([ours]) turned it on.
 */
internal fun locationSwitches(on: Boolean, enabled: Boolean, ours: Boolean): Boolean = if (on) !enabled else enabled && ours
