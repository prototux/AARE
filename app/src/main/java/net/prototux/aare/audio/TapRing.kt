package net.prototux.aare.audio

/**
 * 16-bit little-endian interleaved PCM, as sent to the car, with a model of what the car is playing now.
 * One writer (the capture thread) and one reader (the UI coroutine). write() must never allocate, lock, or block.
 * Allocate everything in the constructor. Use @Volatile positions; a reader racing a writer may see a slightly
 * torn window, which is fine, but it must never throw or read out of bounds.
 */
enum class Mix { MONO, LEFT, RIGHT }

class TapRing(
    val sampleRate: Int,
    val channels: Int,
    capacityMs: Int = 2000,
    val targetLeadMs: Int = DEFAULT_TARGET_LEAD_MS,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels >= 1) { "channels must be >= 1" }
        require(capacityMs > 0) { "capacityMs must be positive" }
        require(targetLeadMs >= 0) { "targetLeadMs must be >= 0" }
    }

    private val capacityFrames: Int = maxOf(1, (sampleRate.toLong() * capacityMs / 1000L).toInt())
    private val targetLeadFrames: Long = sampleRate.toLong() * targetLeadMs / 1000L
    private val leftRing = ShortArray(capacityFrames)
    private val rightRing = ShortArray(capacityFrames)

    @Volatile private var written: Long = 0L
    /** Anchor position in 1/1000ths of a frame (milliframes) so rate adjustments on every chunk never suffer integer truncation. */
    @Volatile private var anchorMilliFrames: Long = 0L
    @Volatile private var anchorMs: Long = 0L
    /** Playhead rate in parts per thousand (1000 = 1.0x nominal sampleRate). Gently steered toward [targetLeadFrames]. */
    @Volatile private var ratePermille: Int = 1000
    @Volatile private var lastWriteMs: Long = 0L
    @Volatile private var hasData: Boolean = false
    @Volatile private var epoch: Long = 0L

    private fun playheadMilliFramesAt(nowMs: Long, w: Long, aMilli: Long, aMs: Long, permille: Int): Long {
        val elapsedMs = maxOf(0L, nowMs - aMs)
        val advancedMilli = elapsedMs * sampleRate * permille / 1_000L
        return minOf(w * 1_000L, aMilli + advancedMilli)
    }

    private fun playheadAt(nowMs: Long, w: Long, aMilli: Long, aMs: Long, permille: Int): Long =
        playheadMilliFramesAt(nowMs, w, aMilli, aMs, permille) / 1_000L

    /** Capture thread: [length] bytes of PCM that just went to the car, at [nowMs] (elapsedRealtime). */
    fun write(buf: ByteArray, offset: Int, length: Int, nowMs: Long) {
        val bytesPerFrame = 2 * channels
        val frames = length / bytesPerFrame
        if (frames <= 0) return

        val w = written
        val newWritten = w + frames
        if (!hasData || nowMs - lastWriteMs > STREAM_GAP_MS) {
            // New stream or after a long pause (>500 ms): re-anchor to start of this stream.
            anchorMilliFrames = w * 1_000L
            anchorMs = nowMs
            ratePermille = 1000
        } else if (nowMs > anchorMs) {
            // Continuous stream: never snap the playhead forward on a late chunk. Advance smoothly
            // from the current playhead and gently trim the rate (±5%) toward targetLeadFrames
            // behind the mid-chunk frontier so capture jitter never causes repeated stalls.
            val curMilli = playheadMilliFramesAt(nowMs, w, anchorMilliFrames, anchorMs, ratePermille)
            anchorMilliFrames = curMilli
            anchorMs = nowMs
            if (targetLeadFrames > 0L) {
                val curPh = curMilli / 1_000L
                val avgLead = w + frames / 2 - curPh
                val errFrames = avgLead - targetLeadFrames
                val adj = (errFrames * MAX_TRIM_PERMILLE / targetLeadFrames)
                    .toInt()
                    .coerceIn(-MAX_TRIM_PERMILLE, MAX_TRIM_PERMILLE)
                ratePermille = 1000 + adj
            }
        }
        lastWriteMs = nowMs

        val cap = capacityFrames
        var idx = (w % cap).toInt()
        var b = offset
        if (channels == 1) {
            for (i in 0 until frames) {
                val s = ((buf[b].toInt() and 0xff) or (buf[b + 1].toInt() shl 8)).toShort()
                leftRing[idx] = s
                rightRing[idx] = s
                b += 2
                idx++
                if (idx == cap) idx = 0
            }
        } else {
            for (i in 0 until frames) {
                val l = ((buf[b].toInt() and 0xff) or (buf[b + 1].toInt() shl 8)).toShort()
                val r = ((buf[b + 2].toInt() and 0xff) or (buf[b + 3].toInt() shl 8)).toShort()
                leftRing[idx] = l
                rightRing[idx] = r
                b += bytesPerFrame
                idx++
                if (idx == cap) idx = 0
            }
        }
        written = newWritten
        hasData = true
    }

    /**
     * UI: fill [dst] (any length, normally FFT_SIZE) with the frames that END at playhead(nowMs) - delayMs (a negative delay reads ahead, up to the newest frame), as [mix], scaled to -1..1 (divide by 32768).
     * Returns false (and leaves dst alone) when there's nothing to show: nothing written yet, or that window is older than the ring holds.
     */
    fun read(nowMs: Long, delayMs: Int, mix: Mix, dst: FloatArray): Boolean {
        val ep = epoch
        if (!hasData) return false
        val n = dst.size
        if (n == 0) return true
        val cap = capacityFrames
        if (n > cap) return false

        val w = written
        val aMilli = anchorMilliFrames
        val aMs = anchorMs
        val permille = ratePermille
        if (w <= 0L) return false

        val ph = playheadAt(nowMs, w, aMilli, aMs, permille)
        // A negative delay reads ahead of the playhead, but never past what's been written.
        val delayFrames = delayMs.toLong() * sampleRate / 1000L
        val endFrame = minOf(ph - delayFrames, w)
        val startFrame = endFrame - n
        if (startFrame < 0L) return false
        if (endFrame > w) return false
        val oldestAvailable = maxOf(0L, w - cap)
        if (startFrame < oldestAvailable) return false

        var idx = (startFrame % cap).toInt()
        val scale = 1f / 32768f
        val halfScale = 0.5f / 32768f

        when {
            channels == 1 || mix == Mix.LEFT -> {
                for (i in 0 until n) {
                    dst[i] = leftRing[idx].toInt() * scale
                    idx++
                    if (idx == cap) idx = 0
                }
            }
            mix == Mix.RIGHT -> {
                for (i in 0 until n) {
                    dst[i] = rightRing[idx].toInt() * scale
                    idx++
                    if (idx == cap) idx = 0
                }
            }
            else -> {
                for (i in 0 until n) {
                    dst[i] = (leftRing[idx].toInt() + rightRing[idx].toInt()) * halfScale
                    idx++
                    if (idx == cap) idx = 0
                }
            }
        }

        if (epoch != ep || !hasData) {
            dst.fill(0f)
            return false
        }
        return true
    }

    /** Sent but (by the model) not yet played, in ms. For the diagnostics log. */
    fun leadMs(nowMs: Long): Long {
        if (!hasData) return 0L
        val w = written
        val ph = playheadAt(nowMs, w, anchorMilliFrames, anchorMs, ratePermille)
        return maxOf(0L, (w - ph) * 1000L / sampleRate)
    }

    fun clear() {
        epoch++
        hasData = false
        written = 0L
        anchorMilliFrames = 0L
        anchorMs = 0L
        ratePermille = 1000
        lastWriteMs = 0L
    }

    companion object {
        const val DEFAULT_TARGET_LEAD_MS = 150
        private const val STREAM_GAP_MS = 500L
        private const val MAX_TRIM_PERMILLE = 50
    }
}
