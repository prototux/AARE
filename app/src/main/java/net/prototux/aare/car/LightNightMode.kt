package net.prototux.aare.car

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
    var night: Boolean = false
        private set
    private var pendingNight: Boolean? = null
    private var pendingSinceMs: Long = 0L

    /**
     * A new lux reading at [nowMs] (a monotonic clock, e.g. `SystemClock.elapsedRealtime()`, so a
     * wall-clock change never causes a jump). Returns true if [night] just changed.
     */
    fun onLux(lux: Float, nowMs: Long): Boolean {
        val wants = when {
            lux <= nightBelowLux -> true
            lux >= dayAboveLux -> false
            else -> night
        }
        if (wants == night) {
            pendingNight = null
            return false
        }
        if (wants != pendingNight) {
            pendingNight = wants
            pendingSinceMs = nowMs
        }
        if (nowMs - pendingSinceMs < stableMs) return false
        night = wants
        pendingNight = null
        return true
    }
}

/**
 * Feeds [LightNightDecider] from the phone's own light sensor while a setting asks for it
 * (Settings.nightSource), instead of the car's own day/night signal. [onNightChanged] is called
 * on the sensor's own thread (as `SensorEventListener` delivers it); the caller hops to the main
 * thread if it needs to.
 */
class LightSensorFeed(context: Context, private val onNightChanged: (Boolean) -> Unit) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val decider = LightNightDecider()
    private var listener: SensorEventListener? = null

    /** A light sensor is actually available to listen to. */
    val available: Boolean get() = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

    fun start() {
        if (listener != null) return
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (decider.onLux(event.values[0], SystemClock.elapsedRealtime())) onNightChanged(decider.night)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        listener = l
    }

    /** Releases the sensor. Call when the session ends, or the setting is switched away from it. */
    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
    }
}
