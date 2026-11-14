package net.prototux.aare.video

import android.os.SystemClock
import android.util.Log
import net.prototux.aare.aap.channels.VideoChannel

/**
 * Adaptive bitrate for the projection: watches how the head unit keeps up and lowers or raises
 * the encoder's target bitrate.
 *
 * Wireless links (Wi-Fi + TCP) are the main case: when the link slows down, the socket backs up,
 * acks come late, frames pile up behind the ack window and finally get dropped (a key frame
 * resync, a visible freeze). Lowering the bitrate early avoids that. We step down fast (-25% after
 * about 1.5 s of congestion) and up slowly (+10% after 10 s without any), between a floor and the
 * bitrate the stream started with, which stays the ceiling.
 *
 * No key frame is asked for after a step down: the rate control adapts within a few frames, and a
 * key frame is the biggest frame there is, the worst thing to send into a congested link. When the
 * backlog does overflow, the channel already drops it and asks for one.
 *
 * Make one per stream (the encoder restarts with each configuration). It does nothing while the
 * projection is hidden, and nothing at all on head units that stopped acking (no signal to go by).
 * A drop overflow stream (head units 6.0 and later) has no acks to go by either, but its socket
 * still backs up when the link is full, so it is watched by the send latency alone.
 * Called on the encoder's thread, from its output callback.
 */
class BitrateController(
    val startBitrate: Int,
    private val params: Params = Params(),
    private val apply: (Int) -> Unit,
) {
    data class Params(
        /** Frames are looked at in windows of this length. */
        val tickMs: Long = 250,
        val downAfterMs: Long = 1_500,
        val upAfterMs: Long = 10_000,
        val downFactor: Double = 0.75,
        val upFactor: Double = 1.10,
        /** A backlog overflow (frames dropped) counts as this much extra congestion. */
        val overflowPenaltyMs: Long = 750,
        /** Mean frames waiting for the ack window: 2 is about 70 ms of added latency at 30 fps. */
        val pendingHigh: Double = 2.0,
        /** Send to ack, including the head unit's decode: well past what any healthy link shows. */
        val ackLatencyHighMs: Double = 300.0,
        /** Session queue to socket write: a TCP socket that blocks means the link is full. */
        val sendLatencyHighMs: Double = 100.0,
        val minFloor: Int = 1_000_000,
        /** A longer pause between frames (projection hidden, encoder stalled) starts a new window. */
        val gapMs: Long = 2_000,
    )

    /** What one window of frames showed. */
    data class Tick(val durationMs: Long, val congested: Boolean, val overflowed: Boolean)

    /** The bitrate, and how long the link has been congested or calm (ms, leaky). */
    data class State(val bitrate: Int, val congestedMs: Long = 0, val calmMs: Long = 0)

    /** The lowest bitrate we go to: a quarter of the start, at least [Params.minFloor], at most the start. */
    val floor: Int = floorFor(startBitrate, params)

    var state = State(startBitrate)
        private set
    val bitrate: Int get() = state.bitrate

    private var windowStart = -1L
    private var lastFrame = -1L
    private var frames = 0
    private var pendingSum = 0L
    private var ackSum = 0L
    private var sendSum = 0L
    private var dropsAtStart = 0L

    /** Call after each frame handed to [ch]. */
    fun onFrame(ch: VideoChannel) = onFrame(
        SystemClock.elapsedRealtime(), active = ch.focused && (!ch.ackless || ch.dropsOverflow),
        pending = ch.pendingFrames, congestionDrops = ch.congestionDrops,
        ackLatencyMs = ch.ackLatencyMs, sendLatencyMs = ch.sendLatencyMs,
    )

    fun onFrame(nowMs: Long, active: Boolean, pending: Int, congestionDrops: Long, ackLatencyMs: Long, sendLatencyMs: Long) {
        if (!active || (lastFrame >= 0 && nowMs - lastFrame > params.gapMs)) {
            // Time hidden or stalled says nothing about the link: start over (keeping the bitrate).
            windowStart = -1
            lastFrame = -1
            state = State(state.bitrate)
            if (!active) return
        }
        if (windowStart < 0) {
            windowStart = nowMs
            frames = 0; pendingSum = 0; ackSum = 0; sendSum = 0
            dropsAtStart = congestionDrops
        }
        lastFrame = nowMs
        frames++
        pendingSum += pending
        ackSum += ackLatencyMs
        sendSum += sendLatencyMs
        val elapsed = nowMs - windowStart
        if (elapsed < params.tickMs) return

        val meanPending = pendingSum.toDouble() / frames
        val meanAck = ackSum.toDouble() / frames
        val meanSend = sendSum.toDouble() / frames
        val tick = Tick(elapsed, congested(meanPending, meanAck, meanSend, params), congestionDrops > dropsAtStart)
        windowStart = -1
        val next = decide(state, tick, startBitrate, floor, params)
        if (next.bitrate != state.bitrate) {
            val why = if (next.bitrate < state.bitrate) {
                "congested: %.1f frames waiting, ack %.0f ms, send %.0f ms%s".format(meanPending, meanAck, meanSend,
                    if (tick.overflowed) ", backlog dropped" else "")
            } else "link calm"
            Log.i(TAG, "video bitrate ${state.bitrate / 1000} -> ${next.bitrate / 1000} kb/s ($why)")
            apply(next.bitrate)
        }
        state = next
    }

    companion object {
        private const val TAG = "AareBitrate"

        fun floorFor(start: Int, p: Params = Params()) = minOf(start, maxOf(p.minFloor, start / 4))

        /** Whether one window of frames shows a link (or head unit) that doesn't keep up. */
        fun congested(meanPending: Double, meanAckMs: Double, meanSendMs: Double, p: Params = Params()) =
            meanPending >= p.pendingHigh || meanAckMs >= p.ackLatencyHighMs || meanSendMs >= p.sendLatencyHighMs

        /**
         * The decision, pure: the next state after one [tick]. Congestion adds up (calm windows only
         * take half their length off, so on-and-off congestion still counts) until it is sustained
         * enough to step down; calm adds up until it is long enough to step up.
         */
        fun decide(s: State, tick: Tick, start: Int, floor: Int, p: Params = Params()): State {
            if (tick.congested || tick.overflowed) {
                val congestedMs = s.congestedMs + tick.durationMs + if (tick.overflowed) p.overflowPenaltyMs else 0L
                if (congestedMs >= p.downAfterMs && s.bitrate > floor) {
                    return State(maxOf(floor, (s.bitrate * p.downFactor).toInt()))
                }
                return State(s.bitrate, congestedMs.coerceAtMost(p.downAfterMs), 0)
            }
            val congestedMs = maxOf(0L, s.congestedMs - tick.durationMs / 2)
            val calmMs = s.calmMs + tick.durationMs
            if (calmMs >= p.upAfterMs && s.bitrate < start) {
                return State(minOf(start, (s.bitrate * p.upFactor).toInt()), congestedMs, 0)
            }
            return State(s.bitrate, congestedMs, calmMs.coerceAtMost(p.upAfterMs))
        }
    }
}
