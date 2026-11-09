package net.prototux.aare.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.prototux.aare.aap.pcm.PacedPcmQueue
import net.prototux.aare.aap.pcm.PcmFormat
import net.prototux.aare.audio.PcmSource

/**
 * The sound of AARE's own video player, straight to the car's media channel: no screen capture,
 * and the phone stays quiet.
 *
 * The player taps its decoded sound, already in the car's format ([mediaFormat]), and [push]es
 * it here. While the player is open ([active]) the car session plays a [Source] on the media
 * channel instead of capturing the phone; when it closes, the session goes back to capturing.
 * With no car session listening ([toCar] false) nothing is pushed anywhere and the player plays
 * on the phone as usual.
 */
object CarPlayerAudio {
    /** The car's media channel format, set by the car session once the car has set it up. */
    @Volatile var mediaFormat: PcmFormat? = null

    /** The player is open: the car session should take its sound on the media channel. */
    @Volatile var active = false
        private set

    /** Called (any thread) when [active] changes; the car session sets it. */
    @Volatile var listener: (() -> Unit)? = null

    @Volatile private var queue: PacedPcmQueue? = null
    private val _toCar = MutableStateFlow(false)
    /** A car session is playing the player's sound: the player mutes itself on the phone. */
    val toCar: StateFlow<Boolean> = _toCar.asStateFlow()

    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        listener?.invoke()
    }

    /** Decoded sound, whole frames in [mediaFormat]: to the car if a session listens, else dropped. */
    fun push(pcm: ByteArray) {
        queue?.push(pcm)
    }

    /** Drops what's queued (seek, pause, a new video): the car shouldn't play stale sound. */
    fun flush() {
        queue?.flush()
    }

    /** The car session's side: a stream paced to real time for the media channel. */
    class Source(private val format: PcmFormat) : PcmSource {
        private var q: PacedPcmQueue? = null

        override fun start(): Boolean {
            val created = PacedPcmQueue(format)
            q = created
            queue?.close()
            queue = created
            _toCar.value = true
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int = q?.read(buf, offset, length) ?: -1

        override fun release() {
            val mine = q ?: return
            q = null
            mine.close()
            // Only clear what's still ours: a newer session may have replaced it.
            if (queue === mine) { queue = null; _toCar.value = false }
        }
    }
}

/**
 * Mixing coefficients from [inCh] to [outCh] channels, as androidx.media3's ChannelMixingMatrix
 * takes them (row-major: input channel rows, output channel columns). Stereo and mono outputs get
 * proper downmixes (5.1 and 7.1 with the usual centre and surround weights, LFE left out); each
 * output's weights sum to at most 1, so a downmix never clips.
 */
fun downmixCoefficients(inCh: Int, outCh: Int): FloatArray {
    require(inCh > 0 && outCh > 0)
    val m = FloatArray(inCh * outCh)
    if (inCh == outCh) { for (i in 0 until inCh) m[i * outCh + i] = 1f; return m }
    if (outCh == 1) { for (i in 0 until inCh) m[i] = 1f / inCh; return m }
    if (outCh == 2) {
        val c = 0.7071f
        // (left, right) weight of each input channel, in Android's channel order.
        val weights: List<Pair<Float, Float>> = when (inCh) {
            1 -> listOf(1f to 1f)
            6 -> listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, c to 0f, 0f to c) // FL FR FC LFE BL BR
            8 -> listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, c to 0f, 0f to c, c to 0f, 0f to c) // + SL SR
            else -> List(inCh) { i -> when (i) { 0 -> 1f to 0f; 1 -> 0f to 1f; else -> 0.5f to 0.5f } }
        }
        val sumL = weights.sumOf { it.first.toDouble() }.toFloat()
        val sumR = weights.sumOf { it.second.toDouble() }.toFloat()
        weights.forEachIndexed { i, (l, r) ->
            m[i * 2] = if (inCh == 1) l else l / maxOf(1f, sumL)
            m[i * 2 + 1] = if (inCh == 1) r else r / maxOf(1f, sumR)
        }
        return m
    }
    // More than two outputs (a car never asks for that): input i to output i % outCh.
    val per = IntArray(outCh)
    for (i in 0 until inCh) per[i % outCh]++
    for (i in 0 until inCh) m[i * outCh + i % outCh] = 1f / per[i % outCh]
    return m
}
