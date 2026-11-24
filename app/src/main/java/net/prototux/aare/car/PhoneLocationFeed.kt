package net.prototux.aare.car

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Feeds the car's GPS to the phone's own location providers while connected, so every app
 * (CoMaps, Google Maps…) sees one position. Otherwise a navigation app mixes the car's position,
 * which AARE gives car apps, with the phone's own fix, and the map jumps between the two.
 *
 * Android only allows it for the app chosen as "mock location app" in Developer options.
 */
class PhoneLocationFeed(context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val providers = PROVIDERS
    private val active = HashSet<String>()
    @Volatile private var failed = false
    /** The session ended ([close]): a fix still on its way from the car mustn't add the providers again. */
    @Volatile private var closed = false

    /** A new car fix (any thread). */
    @Synchronized
    fun push(car: Location) {
        if (failed || closed) return
        if (active.isEmpty()) start()
        for (p in active) {
            val l = Location(car).apply {
                provider = p
                if (!hasAccuracy()) accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            runCatching { lm.setTestProviderLocation(p, l) }.onFailure { Log.w(TAG, "setTestProviderLocation $p", it) }
        }
    }

    // The boolean form works on every Android version; Criteria's POWER_LOW and ACCURACY_FINE have
    // the same values as the ProviderProperties constants lint expects.
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun start() {
        for (p in providers) {
            runCatching {
                lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
                active += p
            }.onFailure { Log.w(TAG, "addTestProvider $p", it) }
        }
        // Not the mock location app: don't keep trying for every fix.
        if (active.isEmpty()) failed = true
    }

    @Synchronized
    fun stop() {
        for (p in active) runCatching { lm.removeTestProvider(p) }
        active.clear()
        failed = false
    }

    /** The session is over: [stop], and later fixes are ignored. */
    @Synchronized
    fun close() {
        closed = true
        stop()
    }

    companion object {
        private const val TAG = "AareLocation"

        /**
         * Removes the car's position left on the phone's providers by a session that crashed: a test
         * provider outlives AARE, and every app would see the car's last position until a reboot.
         * Only when no session runs.
         */
        fun recover(context: Context) {
            if (!allowed(context)) return
            val lm = context.getSystemService(LocationManager::class.java) ?: return
            for (p in PROVIDERS) runCatching { lm.removeTestProvider(p) }
        }

        private val PROVIDERS = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }

        /** AARE is the phone's mock location app (Developer options > Select mock location app). */
        fun allowed(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
                else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
