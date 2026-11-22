package net.prototux.aare.car

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The hysteresis decision for night mode from the phone's ambient light sensor, pure and
 * independent of Android's Sensor APIs so it's unit tested directly: night below
 * [nightBelowLux], day above [dayAboveLux] (a dead zone between them keeps whatever [night]
 * already is, so hovering right at one threshold doesn't flip back and forth), and only once the
 * new reading has held steady for [stableMs] — a few seconds, so a streetlight glinting past or a
 * shadow crossing the sensor for a moment doesn't toggle the car screen.
 */
class LightNightDecider(
    private val nightBelowLux: Float = 10f,
    private val dayAboveLux: Float = 30f,
    private val stableMs: Long = 3_000L,
) {
    /** Null until a first reading has held for [stableMs]: the sensor's first state is reported too. */
    var night: Boolean? = null
        private set
    private var pendingNight: Boolean? = null
    private var pendingSinceMs: Long = 0L

    /**
     * A new lux reading at [nowMs] (a monotonic clock, e.g. `SystemClock.elapsedRealtime()`, so a
     * wall-clock change never causes a jump). Returns true if [night] just changed, including the
     * first stable state (from null).
     */
    fun onLux(lux: Float, nowMs: Long): Boolean {
        val wants = when {
            lux <= nightBelowLux -> true
            lux >= dayAboveLux -> false
            // The dead zone keeps the current state; with none yet, the nearer threshold decides.
            else -> night ?: (lux < (nightBelowLux + dayAboveLux) / 2)
        }
        if (wants == night) {
            pendingNight = null
            return false
        }
        if (wants != pendingNight) {
            pendingNight = wants
            pendingSinceMs = nowMs
        }
        return onTick(nowMs)
    }

    /**
     * No new reading by [nowMs]: commits the pending state once it has held for [stableMs]. Needed
     * because TYPE_LIGHT only reports changes, so a steady room never sends the event that would
     * end the window. Returns true if [night] just changed.
     */
    fun onTick(nowMs: Long): Boolean {
        val wants = pendingNight ?: return false
        if (nowMs - pendingSinceMs < stableMs) return false
        night = wants
        pendingNight = null
        return true
    }

    /** How long from [nowMs] until a pending state would be committed, or null with none pending. */
    fun msUntilStable(nowMs: Long): Long? =
        if (pendingNight == null) null else (stableMs - (nowMs - pendingSinceMs)).coerceAtLeast(0L)
}

/**
 * Feeds [LightNightDecider] from the phone's own light sensor while a setting asks for it
 * (Settings.nightSource), instead of the car's own day/night signal. Sensor events, the timer that
 * ends the stability window and [onNightChanged] all run on the main thread. After [stop] nothing
 * is delivered any more, even a callback already queued.
 */
class LightSensorFeed(context: Context, private val onNightChanged: (Boolean) -> Unit) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val decider = LightNightDecider()
    private var listener: SensorEventListener? = null
    /** Set by [stop]: a queued event or tick that runs afterwards does nothing. */
    private var released = false
    private val tick = Runnable { if (!released && decider.onTick(SystemClock.elapsedRealtime())) report() }

    /** A light sensor is actually available to listen to. */
    val available: Boolean get() = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

    fun start() {
        if (listener != null || released) return
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (released) return
                val now = SystemClock.elapsedRealtime()
                if (decider.onLux(event.values[0], now)) report()
                // A reading still pending: commit it when its window ends, even if no event follows.
                main.removeCallbacks(tick)
                decider.msUntilStable(now)?.let { main.postDelayed(tick, it + 50) }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, main)
        listener = l
    }

    private fun report() {
        decider.night?.let(onNightChanged)
    }

    /** Releases the sensor for good. Call when the session ends, or the setting is switched away from it. */
    fun stop() {
        released = true
        main.removeCallbacks(tick)
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
    }
}
