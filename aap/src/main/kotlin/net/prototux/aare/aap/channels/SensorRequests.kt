package net.prototux.aare.aap.channels

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType

/**
 * Which sensors the car is asked for, and when. One request at a time,
 * the next only once the car answered the last (a SensorResponse doesn't say which sensor it is
 * for) or [TIMEOUT_MS] went by; a sensor is started when something [want]s it and stopped (period
 * [STOP]) when nothing does any more.
 *
 * Thread-safe: [want] comes from the app, [answered] from the reader thread, timeouts from the
 * session's timer.
 */
internal class SensorRequests(
    /** Every sensor the car offers, in the order requests go out when several are due. */
    private val order: List<SensorType>,
    private val periodOf: (SensorType) -> Long,
    private val send: (SensorType, Long) -> Unit,
    /** Runs the task once, [TIMEOUT_MS] from now. */
    private val schedule: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var started = false
    private var wanted: Set<SensorType> = emptySet()
    /** The period each running sensor was last asked for; a stopped one isn't here. */
    private val running = HashMap<SensorType, Long>()
    private var pending: Pair<SensorType, Long>? = null
    /** Tells a timeout from an earlier request apart from one for the current request. */
    private var generation = 0

    /** The channel is open: requests may go out. */
    @Synchronized fun start() {
        started = true
        pump()
    }

    /** The sensors AARE needs now (others the car offers are stopped if they run). */
    @Synchronized fun want(types: Set<SensorType>) {
        wanted = types.filterTo(HashSet()) { it in order }
        pump()
    }

    /** The car answered the request in flight (whatever the status: a refused sensor stays silent). */
    @Synchronized fun answered() {
        if (pending == null) log("sensor response with no request pending")
        done()
    }

    @Synchronized fun stop() {
        started = false
        pending = null
        running.clear()
        generation++
    }

    /** Only for tests and logs: the period each sensor runs at. */
    @Synchronized fun running(): Map<SensorType, Long> = HashMap(running)

    private fun done() {
        val (type, period) = pending ?: return
        pending = null
        if (period == STOP) running.remove(type) else running[type] = period
        pump()
    }

    private fun pump() {
        if (!started || pending != null) return
        val type = order.firstOrNull { desired(it) != running[it] } ?: return
        val period = desired(type) ?: STOP
        pending = type to period
        val gen = ++generation
        send(type, period)
        schedule {
            synchronized(this@SensorRequests) {
                if (gen != generation || pending == null) return@synchronized
                // Counted as done anyway: asking again would repeat forever on a car that never answers.
                log("no answer to the request for ${type.name} within $TIMEOUT_MS ms")
                done()
            }
        }
    }

    /** The period [type] should run at, or null if it should be stopped. */
    private fun desired(type: SensorType): Long? = if (type in wanted) periodOf(type) else null

    companion object {
        /** How long to wait for a SensorResponse before moving on. */
        const val TIMEOUT_MS = 2_000L
        /** min_update_period that stops a sensor. */
        const val STOP = -1L
    }
}
