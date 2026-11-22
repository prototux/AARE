package net.prototux.aare.aap.channels

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType

/**
 * Asks for every sensor in [order] once the channel opens, one request at a time: the next goes
 * only once the car answered the last (a SensorResponse or SensorError doesn't say which sensor it
 * is for) or [TIMEOUT_MS] went by, so a head unit that handles one request at a time isn't flooded
 * at setup. A sensor once asked for keeps running for the whole session.
 *
 * Thread-safe: [answered] comes from the reader thread, timeouts from the session's timer.
 */
internal class SensorRequests(
    /** Every sensor the car offers, in the order the requests go out. */
    private val order: List<SensorType>,
    private val periodOf: (SensorType) -> Long,
    private val send: (SensorType, Long) -> Unit,
    /** Runs the task once, [TIMEOUT_MS] from now. */
    private val schedule: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var started = false
    private val queue = ArrayDeque<SensorType>()
    private var pending: SensorType? = null
    /** Tells a timeout of an earlier request apart from one of the current request. */
    private var generation = 0

    /** The channel is open: the requests go out. */
    @Synchronized fun start() {
        if (started) return
        started = true
        queue.addAll(order)
        pump()
    }

    /** The car answered the request in flight (whatever the status: a refused sensor stays silent). */
    @Synchronized fun answered() {
        if (pending == null) {
            log("sensor answer with no request pending")
            return
        }
        pending = null
        pump()
    }

    @Synchronized fun stop() {
        started = false
        pending = null
        queue.clear()
        generation++
    }

    private fun pump() {
        if (!started || pending != null) return
        val type = queue.removeFirstOrNull() ?: return
        pending = type
        val gen = ++generation
        send(type, periodOf(type))
        schedule {
            synchronized(this@SensorRequests) {
                if (gen != generation || pending == null) return@synchronized
                // Counted as answered anyway: one silent request must not hold back the others.
                log("no answer to the request for ${type.name} within $TIMEOUT_MS ms")
                pending = null
                pump()
            }
        }
    }

    companion object {
        /** How long to wait for the car's answer before the next request. */
        const val TIMEOUT_MS = 2_000L
    }
}
