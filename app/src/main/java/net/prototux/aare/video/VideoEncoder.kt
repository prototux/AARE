package net.prototux.aare.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Hardware H.264 encoder fed by a Surface (a virtual display renders into it).
 *
 * Head units decode Baseline/Constrained Baseline H.264 in Annex-B form, the same thing the
 * official app sends. Output buffers are copied into pooled arrays with [headroom] free bytes in
 * front, so the session can prepend its message header without another copy.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** The starting bitrate; [setBitrate] changes it while running. */
    val bitrate: Int = defaultBitrate(width, height, fps),
    private val headroom: Int = 10,
    private val sink: Sink,
) {
    interface Sink {
        /** SPS/PPS. Sent once before the first frame, and again whenever the encoder restarts. */
        fun onCodecConfig(data: ByteArray, offset: Int, length: Int)

        /** One access unit. [data] has [offset] bytes of headroom; give it back with [recycle]. */
        fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean)

        fun onError(e: Exception)
    }

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    lateinit var inputSurface: Surface
        private set

    private val pool = ArrayDeque<ByteArray>()

    fun start() {
        val t = HandlerThread("aare-video-enc", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        // Built afresh for each attempt (MediaFormat.removeKey is Android 10+).
        fun format(dropped: Set<String>) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            fun int(key: String, value: Int) { if (key !in dropped) setInteger(key, value) }
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Long GOP: the transport is reliable, and we ask for key frames when they're needed.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            int(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            int(MediaFormat.KEY_LEVEL, levelFor(width, height, fps))
            int(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Keep frames flowing on a static screen so the head unit's decoder never stalls.
            if (MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER !in dropped) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 5)
            int(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) int(MediaFormat.KEY_LATENCY, 1)
            int(MediaFormat.KEY_MAX_B_FRAMES, 0)
            // SPS/PPS before every key frame, so a head unit that resets its decoder can resync.
            int(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        // Until the codec runs, a failure must release what was made so far: the caller never
        // gets an encoder to stop, and a leaked codec holds one of the few hardware instances.
        var c: MediaCodec? = null
        var surface: Surface? = null
        try {
            var format = format(emptySet())
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(Callback(), Handler(t.looper))
            // Encoders differ in which tuning keys they accept: drop them step by step until one works.
            val fallbacks = listOf(
                emptyList(),
                listOf(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES),
                listOf(MediaFormat.KEY_LATENCY, MediaFormat.KEY_PRIORITY, MediaFormat.KEY_MAX_B_FRAMES),
                listOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL, MediaFormat.KEY_BITRATE_MODE),
                listOf(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER),
            )
            var configured = false
            var lastError: Exception? = null
            val dropped = HashSet<String>()
            for (drop in fallbacks) {
                dropped += drop
                format = format(dropped)
                try {
                    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    configured = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "encoder ${c.name} rejected $format: ${e.message}")
                    runCatching { c.reset() }
                }
            }
            if (!configured) throw IllegalStateException("no usable H.264 encoder configuration for ${width}x$height@$fps", lastError)
            surface = c.createInputSurface()
            c.start()
            inputSurface = surface
            codec = c
            Log.i(TAG, "encoder ${c.name} ${width}x$height@$fps ${bitrate / 1000} kb/s")
        } catch (e: Throwable) {
            surface?.let { runCatching { it.release() } }
            c?.let { runCatching { it.release() } }
            t.quitSafely()
            thread = null
            throw e
        }
    }

    /** Asks for an IDR frame now (after focus changes, or when the head unit asks). */
    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Changes the target bitrate of the running stream (adaptive bitrate, see [BitrateController]). */
    fun setBitrate(bps: Int) {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
            .onFailure { Log.w(TAG, "can't change the bitrate to $bps: ${it.message}") }
    }

    /** Pauses or resumes output (no frames while the head unit shows its own UI). */
    fun setSuspended(suspended: Boolean) {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0) }) }
        if (!suspended) requestKeyFrame()
    }

    fun recycle(buffer: ByteArray) {
        synchronized(pool) { if (pool.size < 8) pool.addLast(buffer) }
    }

    private fun obtain(size: Int): ByteArray {
        synchronized(pool) {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.size >= size) { it.remove(); return b }
            }
        }
        // round up so buffers get reused across frames of similar size
        return ByteArray(((size + 16383) / 16384) * 16384)
    }

    fun stop() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
        runCatching { inputSurface.release() }
        thread?.quitSafely()
        thread = null
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // Surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset).limit(info.offset + info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val cfg = ByteArray(info.size)
                        buf.get(cfg)
                        sink.onCodecConfig(cfg, 0, cfg.size)
                    } else {
                        val out = obtain(headroom + info.size)
                        buf.get(out, headroom, info.size)
                        sink.onFrame(out, headroom, info.size, info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // released while a callback was in flight
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
            sink.onError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "encoder output format $format")
        }
    }

    companion object {
        private const val TAG = "AareVideoEnc"

        fun defaultBitrate(w: Int, h: Int, fps: Int): Int {
            // ~0.12 bits per pixel per frame: crisp UI text without saturating USB 2 / Wi-Fi,
            // capped at the bitrates head units are specified for (more makes some decoders stall).
            val bpp = 0.12
            val short = minOf(w, h)
            val max = when {
                short <= 480 -> 4_000_000
                short <= 720 -> 6_000_000
                else -> 8_000_000
            }
            return (w * h * fps * bpp).toInt().coerceIn(2_000_000, max)
        }

        /** The lowest H.264 level whose macroblock rate (and frame size) covers the stream. */
        private fun levelFor(w: Int, h: Int, fps: Int): Int {
            val frameMbs = ((w + 15) / 16) * ((h + 15) / 16)
            val mbps = frameMbs * fps
            return when {
                frameMbs > 8_704 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51 // beyond 1080p
                mbps <= 108_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31 // 800x480@60, 720p30
                mbps <= 216_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32 // 720p60
                mbps <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4 // 1080p30
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel42 // 1080p60
            }
        }
    }
}
