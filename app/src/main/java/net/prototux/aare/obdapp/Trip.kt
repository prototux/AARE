package net.prototux.aare.obdapp

import net.prototux.aare.obd.ObdReadings

/**
 * The current trip, summed reading by reading: distance, time, fuel used. Plus the peak of the
 * second gauge (boost or load), which holds a few seconds and then follows the value again.
 */
class Trip {
    var km = 0f; private set
    var liters = 0f; private set
    var startMs = NONE; private set
    private var lastMs = NONE
    var peak: Float? = null; private set
    private var peakMs = 0L

    val minutes get() = if (startMs == NONE) 0f else (lastMs - startMs) / 60_000f

    /** km/L over the trip, once it has gone far enough for the number to settle. */
    val average: Float? get() = if (km >= 0.3f && liters > 0.01f) (km / liters).coerceAtMost(FuelMath.MAX_KM_PER_L) else null

    /** km left: the tank's share × its size × this trip's average (or [fallbackAverage] early on). */
    fun rangeKm(tankPercent: Float?, tankLiters: Int, fallbackAverage: Float): Float? {
        val avg = average ?: fallbackAverage.takeIf { it > 0f } ?: return null
        val tank = tankPercent ?: return null
        return tank / 100f * tankLiters * avg
    }

    fun reset() { km = 0f; liters = 0f; startMs = NONE; lastMs = NONE; peak = null }

    fun feed(r: ObdReadings, car: CarSpec, gaugeValue: Float?, nowMs: Long) {
        if (startMs == NONE) startMs = nowMs
        // A gap (the adapter dropped) counts as at most 5 s, so it doesn't add made-up distance.
        val hours = if (lastMs == NONE) 0f else (nowMs - lastMs).coerceIn(0L, 5_000L) / 3_600_000f
        lastMs = nowMs
        km += (r.speed ?: 0f) * hours
        liters += (FuelMath.litersPerHour(r, car) ?: 0f) * hours

        val v = gaugeValue ?: return
        val p = peak
        if (p == null || v >= p || nowMs - peakMs > PEAK_HOLD_MS) { peak = v; peakMs = nowMs }
    }

    companion object {
        const val PEAK_HOLD_MS = 8_000L
        private const val NONE = Long.MIN_VALUE
    }
}
