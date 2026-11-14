package net.prototux.aare.aap.channels

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType

/**
 * Sends the sensor requests one at a time: the next goes only once the car answered the last (a
 * SensorResponse or SensorError doesn't say which sensor it is for) or [TIMEOUT_MS] went by, so a
 * head unit that handles one request at a time isn't flooded at setup. A sensor once asked for
 * keeps running for the whole session.
 *
 * Thread-safe: [request] may come from any thread, [answered] from the reader thread, timeouts
 * from the session's timer.
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
    private val queue = ArrayDeque<SensorType>()
    /** Asked for (or queued) this session: never asked for twice. */
    private val requested = HashSet<SensorType>()
    private var pending: SensorType? = null
    /** Tells a timeout of an earlier request apart from one of the current request. */
    private var generation = 0

    /** The channel is open: requests may go out. */
    @Synchronized fun start() {
        started = true
        pump()
    }

    /** Asks for [types] the car offers and that weren't asked for yet, in [order]. */
    @Synchronized fun request(types: Collection<SensorType>) {
        for (t in order) if (t in types && requested.add(t)) queue.addLast(t)
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
        requested.clear()
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
