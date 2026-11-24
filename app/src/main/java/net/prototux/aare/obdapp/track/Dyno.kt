package net.prototux.aare.obdapp.track

import kotlin.math.abs

/**
 * Street dyno: power at the wheels from how fast the car speeds up.
 * Force = mass × acceleration + air drag + rolling resistance; power = force × speed.
 * The acceleration comes from the revs (much finer than the OBD's whole km/h): in a gear,
 * speed = rpm × (km/h per rpm of that gear), and that ratio is measured during the pull itself.
 * Valid only while the ratio holds (no shift, no converter slip). Armed, it keeps the highest value
 * of each [BAND] rpm band: that's the curve. For a track or a closed area only.
 */
class Dyno(var massKg: Float = 1300f) {
    var armed = false; private set
    var peakHp = 0f; private set
    var peakRpm = 0f; private set
    val curve = FloatArray((RPM_MAX - RPM_MIN) / BAND + 1)
    private val window = ArrayDeque<Triple<Long, Float, Float>>() // (ms, km/h, rpm)

    var onResult: ((Float, Float) -> Unit)? = null

    fun toggle() {
        if (armed) {
            armed = false
            if (peakHp > 0f) onResult?.invoke(peakHp, peakRpm)
        } else {
            curve.fill(0f); peakHp = 0f; peakRpm = 0f; window.clear()
            armed = true
        }
    }

    fun feed(kmh: Float?, rpm: Float?, ms: Long) {
        if (!armed || kmh == null || rpm == null) return
        window.addLast(Triple(ms, kmh, rpm))
        while (window.size > 3 && ms - window.first().first > WINDOW_MS) window.removeFirst()
        if (window.size < 3 || window.last().first - window.first().first < 500) return
        if (window.any { it.second < 10f }) return

        // km/h per rpm over the window must hold (same gear, drivetrain locked).
        val ratios = window.map { it.second / it.third }
        val ratio = ratios.sorted()[ratios.size / 2]
        if (ratios.any { abs(it - ratio) / ratio > 0.06f }) return

        val rpmPerSecond = slope() ?: return
        // The acceleration is for the middle of the window: use the average revs, not the last.
        val rpmNow = window.map { it.third }.average().toFloat()
        val a = rpmPerSecond * ratio / 3.6f // m/s²
        val v = rpmNow * ratio / 3.6f // m/s
        if (a < 0.3f || v < 3f) return
        // Spinning parts (engine, gearbox, wheels) weigh more in short gears: effective mass
        // = mass × (1.04 + 0.0025 × overall reduction²), reduction = engine rpm ÷ wheel rpm.
        val reduction = 1f / (ratio * WHEEL_RPM_PER_KMH)
        val effectiveMass = massKg * (1.04f + 0.0025f * reduction * reduction)
        val force = effectiveMass * a + 0.5f * AIR_DENSITY * DRAG_AREA * v * v + ROLLING * massKg * 9.81f
        val hp = force * v / WATTS_PER_HP
        val i = ((rpmNow - RPM_MIN) / BAND).toInt()
        if (i !in curve.indices) return
        if (hp > curve[i]) curve[i] = hp
        if (hp > peakHp) { peakHp = hp; peakRpm = rpmNow }
    }

    /** Line through the window's points: rpm per second. */
    private fun slope(): Float? {
        val t0 = window.first().first
        val n = window.size
        val mt = window.sumOf { (it.first - t0) / 1000.0 } / n
        val mr = window.sumOf { it.third.toDouble() } / n
        var up = 0.0; var down = 0.0
        for ((t, _, r) in window) {
            val dt = (t - t0) / 1000.0 - mt
            up += dt * (r - mr); down += dt * dt
        }
        return if (down == 0.0) null else (up / down).toFloat()
    }

    companion object {
        const val RPM_MIN = 1000
        const val RPM_MAX = 8000
        const val BAND = 250
        const val WINDOW_MS = 1500L
        /** A typical small car's drag (Cd × frontal area, m²) and tyre rolling resistance. */
        const val DRAG_AREA = 0.66f
        const val ROLLING = 0.012f
        const val AIR_DENSITY = 1.15f
        /** A typical tyre (~1.9 m around): wheel rpm per km/h. */
        const val WHEEL_RPM_PER_KMH = 8.7f
        /** Metric horsepower (cv / PS). */
        const val WATTS_PER_HP = 735.5f
    }
}
