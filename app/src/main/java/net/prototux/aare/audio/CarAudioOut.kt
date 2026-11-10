package net.prototux.aare.audio

import android.os.Process
import android.os.SystemClock
import android.util.Log
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import net.prototux.aare.DiagLog
import net.prototux.aare.aap.channels.AudioChannel
import kotlin.math.abs

/**
 * Streams phone audio from a [PcmSource] to one of the car's audio channels (media or
 * navigation guidance).
 *
 * The car's stream is opened when sound starts and closed after a few seconds of silence, so the
 * head unit can go back to its own sources, like the official app does. [Focus] asks the car for
 * the right audio focus around it: full focus for media, a transient ducking one for guidance.
 * The stream starts once the car grants focus; the sound captured meanwhile (up to [MAX_QUEUED]
 * chunks) is kept and sent first, so a navigation prompt doesn't lose its first words.
 */
class CarAudioOut(
    private val source: PcmSource,
    private val channel: AudioChannel,
    private val focus: Focus,
    private val onFailed: () -> Unit = {},
) {
    interface Focus {
        /** About to play (true) or done (false). */
        fun request(playing: Boolean)
        /** Whether the car currently lets us play. */
        val granted: Boolean
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val guidance = channel.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE
    private val baseBacklog = if (guidance) MAX_QUEUED else LIVE_BACKLOG
    /**
     * Silence that closes the car's stream. Prompts give the car's sound back quickly (Android Auto
     * waits about 130 ms on guidance); music gets longer, so a pause between tracks doesn't.
     */
    private val silenceMs = if (guidance) GUIDANCE_SILENCE_MS else SILENCE_MS
    /** Until when (elapsedRealtime) to keep the larger backlog after the car reported an underflow. */
    @Volatile private var underflowUntil = 0L
    /** Whether we're actually feeding the car sound right now (read from [onUnderflow], written by the capture thread). */
    @Volatile private var playing = false
    /** When playback last stopped (elapsedRealtime): some head units underflow right after, not mid-stream. */
    @Volatile private var stoppedAtMs = 0L
    /**
     * Chunks kept when the car falls behind. Normally small to stay near real time; for a while
     * after an underflow, bigger, so a slow patch of acks doesn't turn into more gaps.
     */
    private val maxBacklog: Int
        get() = if (SystemClock.elapsedRealtime() < underflowUntil) maxOf(baseBacklog, UNDERFLOW_BACKLOG) else baseBacklog

    /** The car ran out of sound on this stream: trim less for [UNDERFLOW_HOLD_MS]. */
    fun onUnderflow() {
        val now = SystemClock.elapsedRealtime()
        // Some head units report one at every stream end rather than an actual gap: ignore it
        // while we're not playing, or just after we stopped, so it doesn't lag the next stream.
        if (!playing || now - stoppedAtMs < IGNORE_UNDERFLOW_AFTER_STOP_MS) return
        // Some head units report one at every stream end: log only the first of a burst.
        val quiet = now < underflowUntil
        underflowUntil = now + UNDERFLOW_HOLD_MS
        if (!quiet) Log.i(TAG,"car audio underflow on ${channel.streamType} (${channel.underflows} so far), keeping up to $UNDERFLOW_BACKLOG chunks")
    }

    /**
     * The link to the car stalled (the car's ack window is full and sound piles up here): keep
     * more for a while, so a few seconds of slow Wi-Fi delays the music instead of cutting it.
     * Only for media: a late prompt is still a prompt, but its own backlog is already [MAX_QUEUED].
     */
    private fun onCongested(now: Long) {
        if (guidance) return
        val quiet = now < underflowUntil
        underflowUntil = now + UNDERFLOW_HOLD_MS
        if (!quiet) DiagLog.add("car audio: the link to the car is slow, holding up to ${UNDERFLOW_BACKLOG * 20} ms of ${channel.streamType.name.removePrefix("AUDIO_STREAM_").lowercase()} (${channel.droppedChunks} chunks lost so far)")
    }

    fun start() {
        val cfg = channel.config ?: return
        running = true
        thread = Thread({ loop(cfg.samplingRate, cfg.numberOfChannels) }, "aare-audio-${channel.streamType.name.lowercase()}").apply { start() }
    }

    private fun loop(rate: Int, channels: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (!source.start()) {
            // Not a failure to act on if stop() came meanwhile (a slow start outlasts its join).
            val wanted = running
            running = false
            source.release()
            if (wanted) onFailed()
            return
        }
        // ~20 ms chunks: small enough for low latency, big enough to keep the message rate sane.
        val chunk = rate / 50 * channels * 2
        val buf = ByteArray(10 + chunk)
        var lastSound = 0L
        playing = false
        var frames = 0L
        // Chunks (with their timestamp) waiting for focus or for room in the car's ack window.
        val queued = ArrayDeque<Pair<ByteArray, Long>>()
        // The source ended by itself (its service or the audio server died), not by stop(): the
        // next capture method takes over, rather than the car going silent for the rest of the drive.
        var died = false
        fun queue(ts: Long) {
            queued.addLast(buf.copyOf() to ts)
            while (queued.size > MAX_QUEUED) queued.removeFirst()
        }
        try {
            while (running) {
                var got = 0
                while (got < chunk && running) {
                    val n = source.read(buf, 10 + got, chunk - got)
                    if (n < 0) { died = running; running = false; break }
                    if (n == 0) { Thread.sleep(2); continue } // no data yet: yield, don't busy-spin
                    got += n
                }
                if (got < chunk) continue
                val now = SystemClock.elapsedRealtime()
                if (!isSilent(buf, 10, chunk)) lastSound = now
                val ts = frames * 1_000_000L / rate
                frames += chunk / (2 * channels)
                if (!playing && lastSound == now) {
                    focus.request(true)
                    playing = true
                } else if (playing && now - lastSound > silenceMs) {
                    channel.stopPlayback()
                    focus.request(false)
                    playing = false
                    stoppedAtMs = now
                    queued.clear()
                }
                if (!playing) continue
                if (!channel.started) {
                    // Keep the sound until the car grants focus, then open the stream.
                    if (!focus.granted) { queue(ts); continue }
                    channel.startPlayback()
                    if (!channel.started) { queue(ts); continue }
                    // Prompts keep their whole start; media keeps only a little, or it would lag
                    // behind real time (video lip sync) for the whole stream. Use the base backlog,
                    // not a raised one left over from the last stream's underflow.
                    while (queued.size > baseBacklog) queued.removeFirst()
                } else if (!focus.granted) {
                    // Focus lost mid-stream: drop, stale sound later would be worse.
                    queued.clear()
                    continue
                }
                if (queued.isEmpty() && channel.hasRoom) {
                    // A copy: the writer thread sends the array later, and buf is refilled meanwhile.
                    channel.sendPcm(buf.copyOf(), 10, chunk, ts)
                    continue
                }
                // Catching up: send the backlog in order, as fast as the car's ack window allows.
                queue(ts)
                while (queued.isNotEmpty() && channel.hasRoom) queued.removeFirst().let { (b, t) -> channel.sendPcm(b, 10, chunk, t) }
                if (queued.size >= baseBacklog) onCongested(now)
                // The car stays behind (or never acks): stay close to real time. The oldest goes
                // through sendPcm so dropped chunks still count towards ack-less detection.
                while (queued.size > maxBacklog) queued.removeFirst().let { (b, t) -> channel.sendPcm(b, 10, chunk, t) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio capture stopped", e)
        } finally {
            if (playing) { runCatching { channel.stopPlayback() }; focus.request(false); stoppedAtMs = SystemClock.elapsedRealtime() }
            playing = false
            queued.clear()
            source.release()
            if (died) onFailed()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        private const val TAG = "AareAudioOut"
        private const val SILENCE_MS = 3_000L
        /** Long enough for the pause between two sentences of one prompt. */
        private const val GUIDANCE_SILENCE_MS = 800L
        /** About 1 s of 20 ms chunks kept while waiting for the car's focus grant. */
        private const val MAX_QUEUED = 50
        /** Backlog kept once media plays (~240 ms). */
        private const val LIVE_BACKLOG = 12
        /**
         * Backlog after an underflow or a stalled link (~1 s): more latency, fewer gaps. A Wi-Fi
         * stall seen in the car (2026-11-09) held the car's acks for up to 350 ms several times
         * in a row. Bounded by [MAX_QUEUED].
         */
        private const val UNDERFLOW_BACKLOG = MAX_QUEUED
        private const val UNDERFLOW_HOLD_MS = 30_000L
        /** A head unit's end-of-stream underflow lands within this long of us stopping: not a real gap. */
        private const val IGNORE_UNDERFLOW_AFTER_STOP_MS = 1_000L

        /** Digital silence or noise floor (captures deliver zeros when nothing plays). */
        fun isSilent(b: ByteArray, off: Int, len: Int): Boolean {
            var i = off
            val end = off + len - 1
            while (i < end) {
                val s = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
                if (abs(s.toShort().toInt()) > 8) return false
                i += 8 // sampling every 4th sample is plenty to detect sound
            }
            return true
        }
    }
}
