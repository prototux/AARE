package net.prototux.aare.obdapp.track

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * G force from the phone's accelerometer (the phone held firmly in a mount).
 * 1) Up: standing still, the accelerometer feels only gravity, so it learns the vertical.
 * 2) Forward: it starts by assuming the phone stands facing the driver (the car's front is behind
 *    the screen) and corrects itself by comparing with the speed changes from the OBD.
 * Result: longitudinal (+ speeding up, - braking) and lateral (+ to the right), in G.
 */
class GForce {
    private var up: FloatArray? = null
    private var horizontal = floatArrayOf(0f, 0f, 0f)
    private var learnedForward = floatArrayOf(0f, 0f, 0f)
    private var lessons = 0
    private var lastV: Float? = null
    private var lastVMs = 0L
    private var lastTrailMs = 0L

    var longitudinal = 0f; private set
    var lateral = 0f; private set
    var maxAccel = 0f; private set
    var maxBrake = 0f; private set
    var maxLateral = 0f; private set
    val trail = ArrayDeque<Pair<Float, Float>>()
    val calibrated get() = up != null
    val forwardKnown get() = lessons >= 15

    fun resetMax() { maxAccel = 0f; maxBrake = 0f; maxLateral = 0f; trail.clear() }

    fun restart() {
        up = null; lessons = 0; learnedForward = floatArrayOf(0f, 0f, 0f)
        horizontal = floatArrayOf(0f, 0f, 0f); lastV = null
        longitudinal = 0f; lateral = 0f
        resetMax()
    }

    /** The OBD's speed: standing still calibrates the vertical; speeding up or braking teaches where the front is. */
    fun speed(v: Float?, ms: Long) {
        val before = lastV
        val dt = (ms - lastVMs) / 1000f
        lastV = v; lastVMs = ms
        if (v == null || before == null || dt <= 0f || dt > 1f) return
        val aObd = (v - before) / 3.6f / dt
        if (abs(aObd) < 1.0f) return
        // The phone must feel something too, or it'd learn from sensor noise.
        if (length(horizontal) < 0.5f) return
        val sign = if (aObd > 0) 1f else -1f
        for (i in 0..2) learnedForward[i] += horizontal[i] * sign
        lessons++
    }

    /** One accelerometer reading (m/s², the phone's axes). */
    fun sample(x: Float, y: Float, z: Float, ms: Long) {
        val raw = floatArrayOf(x, y, z)
        val still = (lastV ?: 0f) < 1f && abs(length(raw) - G) < 0.4f
        val u = up
        if (u == null) up = raw.copyOf()
        else if (still) for (i in 0..2) u[i] += (raw[i] - u[i]) * 0.02f
        val upN = normalize(up!!) ?: return

        // Take gravity away, and what's left of the vertical: only the push along the ground stays.
        val a = FloatArray(3) { raw[it] - up!![it] }
        val vertical = dot(a, upN)
        for (i in 0..2) {
            val h = a[i] - vertical * upN[i]
            horizontal[i] += (h - horizontal[i]) * 0.25f
        }

        val forward = forward(upN) ?: return
        val right = cross(forward, upN)
        longitudinal = dot(horizontal, forward) / G
        lateral = dot(horizontal, right) / G
        maxAccel = maxOf(maxAccel, longitudinal)
        maxBrake = maxOf(maxBrake, -longitudinal)
        maxLateral = maxOf(maxLateral, abs(lateral))
        if (ms - lastTrailMs >= 50) {
            lastTrailMs = ms
            trail.addLast(lateral to longitudinal)
            while (trail.size > TRAIL) trail.removeFirst()
        }
    }

    private fun forward(upN: FloatArray): FloatArray? {
        val base = if (forwardKnown) learnedForward else floatArrayOf(0f, 0f, -1f)
        val v = dot(base, upN)
        val flat = FloatArray(3) { base[it] - v * upN[it] }
        if (!forwardKnown && length(flat) < 0.5f) return null // phone lying flat: needs to learn first
        return normalize(flat)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun length(a: FloatArray) = sqrt(dot(a, a))
    private fun normalize(a: FloatArray): FloatArray? { val t = length(a); return if (t < 1e-4f) null else FloatArray(3) { a[it] / t } }
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    companion object {
        const val G = 9.81f
        const val TRAIL = 60
    }
}
