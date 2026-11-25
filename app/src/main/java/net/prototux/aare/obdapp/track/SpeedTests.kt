package net.prototux.aare.obdapp.track

import net.prototux.aare.obdapp.ObdHistory

/**
 * The track page's timed runs: 0-100, 80-120, 402 m and braking 100-0. Each runs only when armed:
 * the driver arms it, it waits for the start condition and starts by itself. The exact moments are
 * worked out between two readings, since the car reports its speed only a few times a second.
 * One run is armed at a time. For a track or a closed area only.
 */
abstract class SpeedTest(val kind: ObdHistory.Kind) {
    enum class State { OFF, WAITING, READY, RUNNING }

    var state = State.OFF; protected set
    val armed get() = state != State.OFF
    /** The last result and the best one this session (s or m). */
    var result: Float? = null; protected set
    var best: Float? = null; protected set
    protected var lastMs = 0L
    protected var lastV: Float? = null
    protected var startMs = 0.0

    var onResult: ((ObdHistory.Kind, Float, Float) -> Unit)? = null

    open fun arm() { state = State.WAITING }
    fun disarm() { state = State.OFF }

    fun feed(v: Float, ms: Long) {
        advance(v, ms)
        lastV = v
        lastMs = ms
    }

    protected abstract fun advance(v: Float, ms: Long)

    /** Seconds since the start while running. */
    fun runningSeconds(nowMs: Long) = if (state == State.RUNNING) ((nowMs - startMs) / 1000.0).toFloat() else null

    protected fun finish(value: Float, second: Float = 0f) {
        result = value
        best = best?.let { minOf(it, value) } ?: value
        onResult?.invoke(kind, value, second)
        state = State.WAITING // stays armed for the next one
    }

    /** The moment (ms) the speed went through [target], between the last reading and this one. */
    protected fun crossing(target: Float, v: Float, ms: Long): Double {
        val v0 = lastV ?: return ms.toDouble()
        if (v == v0) return ms.toDouble()
        val f = ((target - v0) / (v - v0)).coerceIn(0f, 1f)
        return lastMs + f * (ms - lastMs).toDouble()
    }

    /** Metres between the last reading and this one (average of the two speeds). */
    protected fun meters(v: Float, ms: Long) = ((lastV ?: v) + v) / 2f / 3.6f * (ms - lastMs) / 1000f
}

/** From one speed to another, speeding up (0-100, 80-120). */
class Acceleration(kind: ObdHistory.Kind, val from: Float, val to: Float) : SpeedTest(kind) {
    private var top = 0f
    private val launch = if (from == 0f) 0.5f else from

    override fun advance(v: Float, ms: Long) {
        when (state) {
            State.OFF -> {}
            State.WAITING -> if (if (from == 0f) v < 0.5f else v < from - 5f) state = State.READY
            State.READY -> {
                val before = lastV
                if (before != null && before < launch && v >= launch) {
                    startMs = crossing(launch, v, ms); top = v; state = State.RUNNING
                }
            }
            State.RUNNING -> {
                top = maxOf(top, v)
                when {
                    v >= to -> finish(((crossing(to, v, ms) - startMs) / 1000.0).toFloat())
                    // Lifted off (lost speed) or took too long: cancelled, waits again.
                    v < top - 5f || ms - startMs > 60_000 -> state = State.WAITING
                }
            }
        }
    }
}

/** A quarter mile (402 m) from standing: time, and the speed at the line. Distance from the car's speed. */
class QuarterMile(private val distance: Float = 402.336f) : SpeedTest(ObdHistory.Kind.QUARTER_MILE) {
    var endSpeed: Float? = null; private set
    var traveled = 0f; private set
    private var top = 0f

    override fun advance(v: Float, ms: Long) {
        when (state) {
            State.OFF -> {}
            State.WAITING -> if (v < 0.5f) state = State.READY
            State.READY -> if ((lastV ?: 0f) < 0.5f && v >= 0.5f) {
                startMs = crossing(0.5f, v, ms)
                traveled = (v / 2f / 3.6f) * ((ms - startMs) / 1000.0).toFloat()
                top = v; state = State.RUNNING
            }
            State.RUNNING -> {
                val before = traveled
                traveled += meters(v, ms)
                top = maxOf(top, v)
                if (traveled >= distance) {
                    val f = (distance - before) / (traveled - before)
                    val s = ((lastMs + f * (ms - lastMs) - startMs) / 1000.0).toFloat()
                    val end = (lastV ?: v) + f * (v - (lastV ?: v))
                    endSpeed = end
                    finish(s, end)
                } else if (v < top - 5f || ms - startMs > 60_000) state = State.WAITING
            }
        }
    }
}

/** Braking from 100 to 0: metres from the moment it went through 100 km/h slowing down. */
class Braking(private val from: Float = 100f) : SpeedTest(ObdHistory.Kind.BRAKE_100_0) {
    var traveled = 0f; private set
    private var lowest = 0f

    override fun advance(v: Float, ms: Long) {
        when (state) {
            State.OFF -> {}
            State.WAITING -> if (v >= from) state = State.READY
            State.READY -> if ((lastV ?: 0f) >= from && v < from) {
                startMs = crossing(from, v, ms)
                traveled = (from + v) / 2f / 3.6f * ((ms - startMs) / 1000.0).toFloat()
                lowest = v; state = State.RUNNING
            }
            State.RUNNING -> {
                traveled += meters(v, ms)
                lowest = minOf(lowest, v)
                if (v < 0.5f) finish(traveled)
                else if (v > lowest + 3f || ms - startMs > 20_000) state = State.WAITING // sped up again: doesn't count
            }
        }
    }
}

class SpeedTests {
    val zeroTo100 = Acceleration(ObdHistory.Kind.ZERO_100, 0f, 100f)
    val from80To120 = Acceleration(ObdHistory.Kind.KMH_80_120, 80f, 120f)
    val quarterMile = QuarterMile()
    val braking = Braking()
    val all: List<SpeedTest> = listOf(zeroTo100, from80To120, quarterMile, braking)

    fun feed(kmh: Float?, ms: Long) { if (kmh != null) all.forEach { it.feed(kmh, ms) } }

    /** A tap on a run: arms it (and disarms the others), or disarms it if it was armed. */
    fun toggle(t: SpeedTest) {
        val was = t.armed
        all.forEach { it.disarm() }
        if (!was) t.arm()
    }

    fun disarmAll() = all.forEach { it.disarm() }
}
