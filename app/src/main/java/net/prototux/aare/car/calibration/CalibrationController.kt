package net.prototux.aare.car.calibration

import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.DiagLog
import net.prototux.aare.car.ProjectionLayout
import kotlin.math.hypot
import kotlin.math.min

/** What the calibration tool is doing, for the phone card and the car overlay. */
sealed interface CalState {
    /** No projection running. */
    data object Unavailable : CalState
    data class Ready(val car: String, val saved: Boolean, val driving: Boolean) : CalState
    /** Step 1: the user moves the frame's edges (pixel [margins] l, t, r, b of a [width] x [height] frame). */
    data class Area(val width: Int, val height: Int, val margins: List<Int>, val carMargins: List<Int>) : CalState
    /** Step 2: target [index] (0..3) at [point] (fractions of the frame); [retry] after a tap that didn't fit. */
    data class Target(val index: Int, val point: Pair<Double, Double>, val retry: Boolean) : CalState
    /** Step 3: the new map is live for this one tap; it's dropped after [secondsLeft]. */
    data class Verify(val point: Pair<Double, Double>, val secondsLeft: Int) : CalState
    data class Result(val outcome: Outcome) : CalState

    enum class Outcome { SAVED, MISSED, TIMEOUT, CANCELLED, NO_SPREAD, INTERRUPTED, RESET }
}

/** The phone UI's way to the calibration of the car session that is projecting (main thread). */
object CalibrationHub {
    private val _state = MutableStateFlow<CalState>(CalState.Unavailable)
    val state: StateFlow<CalState> = _state
    internal var controller: CalibrationController? = null

    internal fun publish(from: CalibrationController, s: CalState) { if (controller === from) _state.value = s }
    internal fun detach(from: CalibrationController) { if (controller === from) { controller = null; _state.value = CalState.Unavailable } }

    fun start() = controller?.start()
    fun setMargins(m: List<Int>) = controller?.setMargins(m)
    fun next() = controller?.next()
    fun cancel() = controller?.cancel()
    fun dismiss() = controller?.dismiss()
    fun resetSaved() = controller?.resetSaved()
}

/**
 * The calibration tool of one car session: lets the user set the visible area, takes the raw
 * position of a tap on each of 4 targets, fits the touch map, then keeps it only if a tap on a
 * centre target lands on it within [VERIFY_SECONDS]; otherwise the previous mapping stays.
 *
 * Main thread, except [capturing] and [onTouchFromReader] (the session's reader thread). While
 * [capturing], touches go here and not to the car UI: with a wrong mapping it would get random taps.
 */
class CalibrationController(private val host: Host, private val main: Scheduler) {
    /** The main thread's queue (a [Handler] in the app, a fake clock in tests). */
    interface Scheduler {
        fun post(r: Runnable)
        fun postDelayed(r: Runnable, ms: Long)
        fun remove(r: Runnable)

        companion object {
            fun of(h: Handler) = object : Scheduler {
                override fun post(r: Runnable) { h.post(r) }
                override fun postDelayed(r: Runnable, ms: Long) { h.postDelayed(r, ms) }
                override fun remove(r: Runnable) { h.removeCallbacks(r) }
            }
        }
    }

    interface Host {
        val car: String
        val driving: Boolean
        /** The running projection's frame and the margins the car declared; null when there is none. */
        val projection: Projection?
        /** Stores [c] for [car] (null: removes it) and lays the projection out again with it. */
        fun apply(c: TouchCalibration?)
    }
    class Projection(val width: Int, val height: Int, val carLayout: ProjectionLayout, val saved: TouchCalibration?)

    @Volatile var capturing = false
        private set
    private var state: CalState = CalState.Unavailable
    private var frame: Projection? = null
    private var margins = listOf(0, 0, 0, 0)
    private var targets = emptyList<Pair<Double, Double>>()
    private val taps = arrayOfNulls<Pair<Double, Double>>(4)
    private var retries = 0
    private var candidate: TouchCalibration? = null
    private var verifyLeft = 0

    private fun set(s: CalState) { state = s; CalibrationHub.publish(this, s) }
    private fun log(line: String) = DiagLog.add("calibration: $line")
    private fun ready() = CalState.Ready(host.car, frame?.saved != null, host.driving)

    /** A projection (re)started: any calibration in progress was for the old one. */
    fun onProjection(p: Projection) {
        val wasActive = capturing || state is CalState.Area
        frame = p
        CalibrationHub.controller = this
        if (wasActive) finish(CalState.Outcome.INTERRUPTED) else if (state !is CalState.Result) set(ready())
    }

    fun close() {
        if (capturing || state is CalState.Area) log("session ended during calibration, nothing saved")
        stopCapture()
        CalibrationHub.detach(this)
    }

    fun start() {
        val p = frame ?: return
        if (host.driving) { set(ready()); return }
        margins = p.carLayout.let { listOf(it.marginLeft, it.marginTop, it.marginRight, it.marginBottom) }
        // Start from what is in use: a saved area, or the car's.
        p.saved?.margins(p.width, p.height)?.let { margins = it.toList() }
        log("started for ${host.car}, frame ${p.width}x${p.height}, car margins ${p.carLayout.describe().ifBlank { " none" }}")
        set(CalState.Area(p.width, p.height, margins, carMargins(p)))
    }

    private fun carMargins(p: Projection) = p.carLayout.let { listOf(it.marginLeft, it.marginTop, it.marginRight, it.marginBottom) }

    fun setMargins(m: List<Int>) {
        val p = frame ?: return
        if (state !is CalState.Area || m.size != 4) return
        // Each side within the frame, leaving at least a quarter of it visible.
        val l = m[0].coerceIn(0, p.width * 3 / 8); val r = m[2].coerceIn(0, p.width * 3 / 8)
        val t = m[1].coerceIn(0, p.height * 3 / 8); val b = m[3].coerceIn(0, p.height * 3 / 8)
        margins = listOf(l, t, r, b)
        set(CalState.Area(p.width, p.height, margins, carMargins(p)))
    }

    /** From the visible area to the targets. */
    fun next() {
        val p = frame ?: return
        if (state !is CalState.Area) return
        log("visible area: margins l${margins[0]} t${margins[1]} r${margins[2]} b${margins[3]}")
        targets = CalibrationMath.targets(p.width, p.height, margins.toIntArray())
        taps.fill(null); retries = 0
        capturing = true
        set(CalState.Target(0, targets[0], retry = false))
    }

    fun cancel() = finish(CalState.Outcome.CANCELLED)

    fun dismiss() { if (state is CalState.Result) set(ready()) }

    fun resetSaved() {
        if (capturing || state is CalState.Area) return
        log("reset for ${host.car}: back to the car's own values")
        host.apply(null)
        set(CalState.Result(CalState.Outcome.RESET))
    }

    /** Reader thread: the first finger's touch-down position, raw. */
    fun onTouchFromReader(e: TouchEvent) {
        if (e.action != PointerAction.ACTION_DOWN || e.pointerDataCount == 0) return
        val p = e.getPointerData(0)
        val x = (p.x.toLong() and 0xffffffffL).toDouble()
        val y = (p.y.toLong() and 0xffffffffL).toDouble()
        main.post { onDown(x, y) }
    }

    private fun onDown(x: Double, y: Double) {
        when (val s = state) {
            is CalState.Target -> {
                taps[s.index] = x to y
                log("target ${s.index + 1} at (%.4f, %.4f): raw (%.0f, %.0f)".format(s.point.first, s.point.second, x, y))
                val next = taps.indexOfFirst { it == null }
                if (next >= 0) set(CalState.Target(next, targets[next], retry = false)) else fitTaps()
            }
            is CalState.Verify -> {
                val c = candidate ?: return
                val p = frame ?: return
                val mx = c.x(x, y); val my = c.y(x, y)
                val miss = hypot((mx - s.point.first) * p.width, (my - s.point.second) * p.height)
                val visible = min(p.width - margins[0] - margins[2], p.height - margins[1] - margins[3])
                log("check tap: raw (%.0f, %.0f) -> (%.4f, %.4f), %.0f px from the centre target".format(x, y, mx, my, miss))
                if (miss <= visible * HIT_RADIUS) {
                    stopCapture()
                    log("saved for ${host.car}: ${c.describe()}")
                    host.apply(c)
                    set(CalState.Result(CalState.Outcome.SAVED))
                } else {
                    finish(CalState.Outcome.MISSED)
                }
            }
            else -> Unit
        }
    }

    private fun fitTaps() {
        val raw = taps.map { it!! }
        when (val f = CalibrationMath.fit(raw, targets)) {
            is Fit.Ok -> {
                val p = frame ?: return
                val c = TouchCalibration(f.ax, f.bx, f.cx, f.ay, f.by, f.cy,
                    margins[0].toDouble() / p.width, margins[1].toDouble() / p.height,
                    margins[2].toDouble() / p.width, margins[3].toDouble() / p.height, p.width, p.height)
                log("fitted, max error %.4f of the frame: ${c.describe()}".format(f.maxError))
                candidate = c
                val centre = CalibrationMath.centre(p.width, p.height, margins.toIntArray())
                verifyLeft = VERIFY_SECONDS
                set(CalState.Verify(centre, verifyLeft))
                main.postDelayed(tick, 1000)
            }
            is Fit.Inconsistent -> {
                retries++
                log("tap ${f.worst + 1} doesn't fit the others (off by %.3f of the frame), retry $retries".format(f.error))
                if (retries > MAX_RETRIES) {
                    // Probably more than one bad tap: start the targets over.
                    taps.fill(null); retries = 0
                    set(CalState.Target(0, targets[0], retry = true))
                } else {
                    taps[f.worst] = null
                    set(CalState.Target(f.worst, targets[f.worst], retry = true))
                }
            }
            Fit.Degenerate -> {
                log("the 4 taps don't span an area: the car may not report where the screen is touched")
                finish(CalState.Outcome.NO_SPREAD)
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = state as? CalState.Verify ?: return
            verifyLeft--
            if (verifyLeft <= 0) finish(CalState.Outcome.TIMEOUT)
            else { set(s.copy(secondsLeft = verifyLeft)); main.postDelayed(this, 1000) }
        }
    }

    private fun stopCapture() {
        capturing = false
        candidate = null
        main.remove(tick)
    }

    /** Ends without saving: the mapping in use before stays. */
    private fun finish(o: CalState.Outcome) {
        val was = state
        stopCapture()
        if (was is CalState.Area || was is CalState.Target || was is CalState.Verify) log("not saved: ${o.name.lowercase()}")
        set(CalState.Result(o))
    }

    companion object {
        const val VERIFY_SECONDS = 15
        /** A check tap counts within this fraction of the visible area's short side from the target. */
        const val HIT_RADIUS = 0.1
        const val MAX_RETRIES = 2
    }
}
