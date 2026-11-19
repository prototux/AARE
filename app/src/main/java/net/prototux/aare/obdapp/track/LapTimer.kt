package net.prototux.aare.obdapp.track

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Lap timer from GPS. Armed, the first point on the move becomes the start line (across the way the
 * car goes). Each time the car crosses it the same way, a lap closes. GPS gives about one point a
 * second, so the crossing is worked out between two points. The fastest lap becomes the drawing of
 * the track and the reference for the delta (time gained or lost at this point of the track).
 */
class LapTimer {
    enum class State { OFF, WAITING_START, ON_LAP }

    data class Point(val x: Float, val y: Float, val seconds: Float, val meters: Float, val kmh: Float)

    var state = State.OFF; private set
    var laps = 0; private set
    var last: Float? = null; private set
    var best: Float? = null; private set
    var bestLap: List<Point>? = null; private set
    var lastSectors: List<Float>? = null; private set
    var bestSectors: List<Float>? = null; private set
    val currentLap = ArrayList<Point>()
    var line: Pair<Point, FloatArray>? = null; private set

    var onLap: ((Float) -> Unit)? = null

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var hasOrigin = false
    private var startMs = 0.0
    private var previous: Triple<Float, Float, Long>? = null

    /** What to draw: the best lap, or the lap so far before there is one. */
    val outline: List<Point> get() = bestLap ?: currentLap.toList()

    fun arm() { reset(); state = State.WAITING_START }
    fun disarm() { state = State.OFF }

    fun reset() {
        laps = 0; last = null; best = null; bestLap = null; lastSectors = null; bestSectors = null
        currentLap.clear(); line = null; hasOrigin = false; previous = null
    }

    fun secondsOnLap(nowMs: Long) = if (state == State.ON_LAP) ((nowMs - startMs) / 1000.0).toFloat() else null

    /** + = slower than the best lap at this point of the track; - = faster. */
    fun delta(): Float? {
        val ref = bestLap ?: return null
        val now = currentLap.lastOrNull() ?: return null
        val tRef = timeAt(ref, now.meters) ?: return null
        return now.seconds - tRef
    }

    fun point(lat: Double, lon: Double, ms: Long, kmh: Float) {
        if (state == State.OFF) return
        if (!hasOrigin) { lat0 = lat; lon0 = lon; hasOrigin = true }
        val x = ((lon - lon0) * cos(Math.toRadians(lat0)) * 111_320.0).toFloat()
        val y = ((lat - lat0) * 110_540.0).toFloat()
        val prev = previous
        previous = Triple(x, y, ms)
        if (prev == null) return
        val (x0, y0, ms0) = prev

        if (state == State.WAITING_START) {
            val d = distance(x0, y0, x, y)
            if (kmh < 5f || d < 2f) return
            line = Point(x, y, 0f, 0f, kmh) to floatArrayOf((x - x0) / d, (y - y0) / d)
            startMs = ms.toDouble()
            currentLap.clear()
            currentLap += Point(x, y, 0f, 0f, kmh)
            state = State.ON_LAP
            return
        }

        val (p, dir) = line ?: return
        val s0 = (x0 - p.x) * dir[0] + (y0 - p.y) * dir[1]
        val s1 = (x - p.x) * dir[0] + (y - p.y) * dir[1]
        val lastPoint = currentLap.last()
        if (s0 < 0f && s1 >= 0f) {
            val f = -s0 / (s1 - s0)
            val qx = x0 + f * (x - x0)
            val qy = y0 + f * (y - y0)
            val side = abs((qx - p.x) * -dir[1] + (qy - p.y) * dir[0])
            val tCross = ms0 + f * (ms - ms0).toDouble()
            val seconds = ((tCross - startMs) / 1000.0).toFloat()
            val meters = lastPoint.meters + distance(lastPoint.x, lastPoint.y, qx, qy)
            if (side <= HALF_LINE_M && seconds >= MIN_LAP_S && meters >= MIN_LAP_M) {
                currentLap += Point(qx, qy, seconds, meters, kmh)
                closeLap(seconds)
                startMs = tCross
                currentLap.clear()
                currentLap += Point(qx, qy, 0f, 0f, kmh)
            }
        }
        val l = currentLap.last()
        currentLap += Point(x, y, ((ms - startMs) / 1000.0).toFloat(), l.meters + distance(l.x, l.y, x, y), kmh)
    }

    private fun closeLap(seconds: Float) {
        laps++
        last = seconds
        val total = currentLap.last().meters
        val t1 = timeAt(currentLap, total / 3f) ?: 0f
        val t2 = timeAt(currentLap, total * 2f / 3f) ?: 0f
        val sectors = listOf(t1, t2 - t1, seconds - t2)
        lastSectors = sectors
        bestSectors = bestSectors?.let { b -> List(3) { minOf(b[it], sectors[it]) } } ?: sectors
        if (best == null || seconds < best!!) { best = seconds; bestLap = currentLap.toList() }
        onLap?.invoke(seconds)
    }

    private fun timeAt(lap: List<Point>, meters: Float): Float? {
        if (lap.size < 2 || meters > lap.last().meters) return null
        val i = lap.indexOfFirst { it.meters >= meters }.coerceAtLeast(1)
        val a = lap[i - 1]; val b = lap[i]
        val f = if (b.meters == a.meters) 0f else (meters - a.meters) / (b.meters - a.meters)
        return a.seconds + f * (b.seconds - a.seconds)
    }

    private fun distance(x0: Float, y0: Float, x1: Float, y1: Float) = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))

    companion object {
        /** Metres each side of the line (GPS error + track width). */
        const val HALF_LINE_M = 25f
        const val MIN_LAP_S = 15f
        const val MIN_LAP_M = 150f
    }
}
