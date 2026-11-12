package net.prototux.aare.car.calibration

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A head unit's touch calibration, measured by the user with the calibration tool.
 *
 * Touch: an affine map from the raw panel coordinates the car sends to the video frame, in
 * fractions of the frame (0..1):
 *   x = ax * raw.x + bx * raw.y + cx,   y = ay * raw.x + by * raw.y + cy
 * Affine covers a wrong range and offset (the usual bug), swapped or mirrored axes and a slight tilt.
 *
 * Visible area: the margins (fractions of the frame) the user set so the UI fills exactly what the
 * car shows; they replace the ones the car declares.
 *
 * Both hold only for the [width] x [height] video it was measured on: some head units report touches
 * in pixels of the video they are sent, so another resolution needs its own calibration.
 */
data class TouchCalibration(
    val ax: Double, val bx: Double, val cx: Double,
    val ay: Double, val by: Double, val cy: Double,
    val marginLeft: Double, val marginTop: Double, val marginRight: Double, val marginBottom: Double,
    val width: Int, val height: Int,
) {
    fun fits(width: Int, height: Int) = width == this.width && height == this.height

    fun x(rawX: Double, rawY: Double) = ax * rawX + bx * rawY + cx
    fun y(rawX: Double, rawY: Double) = ay * rawX + by * rawY + cy

    /** Margins in pixels of a [width] x [height] frame. */
    fun margins(width: Int, height: Int) = intArrayOf(
        Math.round(marginLeft * width).toInt(), Math.round(marginTop * height).toInt(),
        Math.round(marginRight * width).toInt(), Math.round(marginBottom * height).toInt(),
    )

    fun encode(): String = listOf(VERSION, ax, bx, cx, ay, by, cy, marginLeft, marginTop, marginRight, marginBottom, width, height).joinToString(";")

    fun describe() = "${width}x$height x=%.6g*rx%+.6g*ry%+.4f y=%.6g*rx%+.6g*ry%+.4f margins l%.4f t%.4f r%.4f b%.4f"
        .format(ax, bx, cx, ay, by, cy, marginLeft, marginTop, marginRight, marginBottom)

    companion object {
        private const val VERSION = "1"

        fun decode(s: String?): TouchCalibration? {
            val p = s?.split(";") ?: return null
            if (p.size != 13 || p[0] != VERSION) return null
            val v = p.subList(1, 11).map { it.toDoubleOrNull() ?: return null }
            if (v.any { !it.isFinite() }) return null
            val w = p[11].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val h = p[12].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return TouchCalibration(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], w, h)
        }
    }
}

/** Result of fitting the touch map to the taps of the calibration targets. */
sealed interface Fit {
    data class Ok(val ax: Double, val bx: Double, val cx: Double, val ay: Double, val by: Double, val cy: Double, val maxError: Double) : Fit
    /** The taps don't span an area (all at the same place, or on one line): no map can be fitted. */
    data object Degenerate : Fit
    /** The taps don't agree with each other: [worst] (index) is the one to tap again. */
    data class Inconsistent(val worst: Int, val error: Double) : Fit
}

object CalibrationMath {
    /** Largest distance (fraction of the frame) between a target and where the fitted map puts its tap. */
    const val MAX_ERROR = 0.04

    /**
     * Least-squares affine fit from [raw] panel points to [screen] points (fractions of the frame).
     * Raw points are centred and scaled first: panels report anything from 0..800 to 0..2^32.
     */
    fun fit(raw: List<Pair<Double, Double>>, screen: List<Pair<Double, Double>>): Fit {
        require(raw.size == screen.size && raw.size >= 3)
        val mx = raw.sumOf { it.first } / raw.size
        val my = raw.sumOf { it.second } / raw.size
        val s = max(raw.maxOf { abs(it.first - mx) }, raw.maxOf { abs(it.second - my) })
        if (s == 0.0) return Fit.Degenerate
        val u = raw.map { (it.first - mx) / s to (it.second - my) / s }

        // Normal equations of [u v 1] * [a b c]^T = target, for x and y.
        var suu = 0.0; var suv = 0.0; var svv = 0.0; var su = 0.0; var sv = 0.0
        for ((a, b) in u) { suu += a * a; suv += a * b; svv += b * b; su += a; sv += b }
        val n = u.size.toDouble()
        val m = arrayOf(doubleArrayOf(suu, suv, su), doubleArrayOf(suv, svv, sv), doubleArrayOf(su, sv, n))
        val det = det3(m)
        // Normalised coordinates are within -1..1: a determinant this small means the taps are collinear.
        if (abs(det) < 1e-6) return Fit.Degenerate
        fun solve(t: List<Double>): DoubleArray {
            val r = doubleArrayOf(u.indices.sumOf { u[it].first * t[it] }, u.indices.sumOf { u[it].second * t[it] }, t.sum())
            return DoubleArray(3) { col -> det3(Array(3) { row -> m[row].copyOf().also { it[col] = r[row] } }) / det }
        }
        val px = solve(screen.map { it.first })
        val py = solve(screen.map { it.second })
        // Back from normalised to raw: a*(rx-mx)/s + b*(ry-my)/s + c.
        val ax = px[0] / s; val bx = px[1] / s; val cx = px[2] - ax * mx - bx * my
        val ay = py[0] / s; val by = py[1] / s; val cy = py[2] - ay * mx - by * my

        val errors = raw.indices.map { i ->
            hypot(ax * raw[i].first + bx * raw[i].second + cx - screen[i].first, ay * raw[i].first + by * raw[i].second + cy - screen[i].second)
        }
        val maxError = errors.max()
        if (maxError > MAX_ERROR) return Fit.Inconsistent(outlier(raw, screen), maxError)
        return Fit.Ok(ax, bx, cx, ay, by, cy, maxError)
    }

    /**
     * Which tap is off. The affine fit can't say: with 4 taps it spreads one bad tap's error over
     * all of them. A simpler model can: each screen axis from one raw axis (scale and offset, axes
     * possibly swapped or mirrored). Leave each tap out in turn: the 3 others agree best when the
     * one left out is the bad one.
     */
    internal fun outlier(raw: List<Pair<Double, Double>>, screen: List<Pair<Double, Double>>): Int =
        raw.indices.minBy { out ->
            val r = raw.filterIndexed { i, _ -> i != out }
            val s = screen.filterIndexed { i, _ -> i != out }
            fun axis(target: List<Double>) = minOf(lineError(r.map { it.first }, target), lineError(r.map { it.second }, target))
            max(axis(s.map { it.first }), axis(s.map { it.second }))
        }

    /** Largest residual of the least-squares line t = a * v + b. */
    private fun lineError(v: List<Double>, t: List<Double>): Double {
        val mv = v.average(); val mt = t.average()
        val sv = v.sumOf { (it - mv) * (it - mv) }
        // A raw axis that doesn't vary can't explain a screen axis that does.
        if (sv == 0.0) return t.maxOf { abs(it - mt) }
        val a = v.indices.sumOf { (v[it] - mv) * (t[it] - mt) } / sv
        return v.indices.maxOf { abs(a * (v[it] - mv) + mt - t[it]) }
    }

    private fun det3(m: Array<DoubleArray>) =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    /**
     * Where the targets go, as fractions of the frame: inset [inset] of the visible area from its
     * corners (panels are often unreliable at the very edge), clockwise from the top left.
     */
    fun targets(width: Int, height: Int, margins: IntArray, inset: Double = 0.12): List<Pair<Double, Double>> {
        val l = margins[0].toDouble(); val t = margins[1].toDouble()
        val r = (width - margins[2]).toDouble(); val b = (height - margins[3]).toDouble()
        val dx = (r - l) * inset; val dy = (b - t) * inset
        return listOf(l + dx to t + dy, r - dx to t + dy, r - dx to b - dy, l + dx to b - dy)
            .map { it.first / width to it.second / height }
    }

    /** The check target: the centre of the visible area, as fractions of the frame. */
    fun centre(width: Int, height: Int, margins: IntArray) =
        (margins[0] + (width - margins[0] - margins[2]) / 2.0) / width to (margins[1] + (height - margins[1] - margins[3]) / 2.0) / height
}
