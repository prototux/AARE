package net.prototux.aare.aap.pcm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** 16-bit little-endian PCM, the only encoding the car's audio channels take. */
data class PcmFormat(val rate: Int, val channels: Int) {
    init { require(rate > 0 && channels in 1..2) { "unsupported PCM format $rate Hz x $channels" } }

    val bytesPerFrame: Int get() = 2 * channels

    /** Playing time of [bytes] of this format, in nanoseconds. */
    fun nanosFor(bytes: Int): Long = bytes.toLong() / bytesPerFrame * 1_000_000_000L / rate

    /** Whole frames of this format lasting about [millis]. */
    fun bytesFor(millis: Int): Int = rate.toLong().times(millis).div(1000).toInt() * bytesPerFrame
}

/** A decoded WAV file: its format and its raw samples. */
class Wav(val format: PcmFormat, val pcm: ByteArray) {
    companion object {
        /**
         * Reads a RIFF/WAVE file of 16-bit PCM, as text-to-speech engines write with
         * `synthesizeToFile`. Returns null for anything else (other encodings, a truncated header).
         * Chunks other than `fmt ` and `data` are skipped. A `data` chunk whose length is still 0
         * or too big (an engine killed before it fixed the header) is read to the end of the file.
         */
        fun parse(bytes: ByteArray): Wav? {
            if (bytes.size < 12) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
            var pos = 12
            var format: PcmFormat? = null
            while (pos + 8 <= bytes.size) {
                val id = tag(bytes, pos)
                val len = b.getInt(pos + 4).toLong() and 0xffffffffL
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        if (len < 16 || body + 16 > bytes.size) return null
                        val encoding = b.getShort(body).toInt() and 0xffff
                        val channels = b.getShort(body + 2).toInt() and 0xffff
                        val rate = b.getInt(body + 4)
                        val bits = b.getShort(body + 14).toInt() and 0xffff
                        // 1 = integer PCM; 0xFFFE = WAVE_FORMAT_EXTENSIBLE, used by some engines for plain PCM too.
                        if ((encoding != 1 && encoding != 0xFFFE) || bits != 16 || channels !in 1..2 || rate <= 0) return null
                        format = PcmFormat(rate, channels)
                    }
                    "data" -> {
                        val f = format ?: return null
                        val available = bytes.size - body
                        val n = if (len == 0L || len > available) available else len.toInt()
                        val whole = n - n % f.bytesPerFrame
                        return Wav(f, bytes.copyOfRange(body, body + whole))
                    }
                }
                // Chunks are padded to an even length.
                pos = (body + len + (len and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            return null
        }

        private fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
    }
}

object PcmConvert {
    /**
     * Converts [pcm] from [from] to [to]: mono and stereo either way, and the sample rate by linear
     * interpolation. Plenty for speech going to a car's guidance channel (typically 16 kHz mono);
     * not meant for music.
     */
    fun convert(pcm: ByteArray, from: PcmFormat, to: PcmFormat): ByteArray {
        if (from == to) return pcm.copyOf()
        val inFrames = pcm.size / from.bytesPerFrame
        if (inFrames == 0) return ByteArray(0)
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        // Mono first: averaging stereo halves avoids clipping, and resampling one channel is cheaper.
        val mono = FloatArray(inFrames) { i ->
            if (from.channels == 1) src.get(i).toFloat()
            else (src.get(2 * i).toFloat() + src.get(2 * i + 1).toFloat()) / 2f
        }
        val resampled = if (from.rate == to.rate) mono else resample(mono, from.rate, to.rate)
        val out = ByteBuffer.allocate(resampled.size * to.bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (s in resampled) {
            val v = s.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            repeat(to.channels) { out.putShort(v) }
        }
        return out.array()
    }

    private fun resample(x: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        val outFrames = (x.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val step = fromRate.toDouble() / toRate
        return FloatArray(outFrames) { i ->
            val p = i * step
            val j = p.toInt().coerceAtMost(x.size - 1)
            val k = (j + 1).coerceAtMost(x.size - 1)
            val t = (p - j).toFloat()
            x[j] + (x[k] - x[j]) * t
        }
    }
}
