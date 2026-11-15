package net.prototux.aare.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import net.prototux.aare.aap.channels.AudioChannel

/**
 * Encodes the captured PCM to AAC-LC for a head unit that only takes AAC (#82): Android's AAC
 * encoder ("audio/mp4a-latm", which every Android device has), used synchronously from the audio
 * thread. Each encoded frame (1024 samples per channel) comes out with [AudioChannel.FRAME_HEADROOM]
 * free bytes in front of it, for [AudioChannel.sendFrame].
 */
class AacEncoder(private val rate: Int, private val channels: Int, maxInput: Int) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels > 1) STEREO_BITRATE else MONO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInput)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /**
     * Encodes [length] bytes of 16-bit PCM from [pcm] (at [offset]) stamped [timestampUs], and hands
     * every frame the encoder has ready to [out] (buffer, offset of the frame, its length, timestamp).
     * The encoder holds a frame or two back, so a call may give nothing, or more than one frame.
     */
    fun encode(pcm: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ByteArray, Int, Int, Long) -> Unit) {
        drain(out)
        val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
        if (index < 0) {
            Log.w(TAG, "AAC encoder has no input buffer free, dropping ${length} bytes")
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        val n = minOf(length, input.remaining())
        input.put(pcm, offset, n)
        codec.queueInputBuffer(index, 0, n, timestampUs, 0)
        drain(out)
    }

    private fun drain(out: (ByteArray, Int, Int, Long) -> Unit) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return // no frame ready (or a format change, which we don't need)
            try {
                // The AudioSpecificConfig: the channel sends its own when the stream starts.
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size <= 0) continue
                val buf = codec.getOutputBuffer(index) ?: continue
                val frame = ByteArray(AudioChannel.FRAME_HEADROOM + info.size)
                buf.position(info.offset)
                buf.get(frame, AudioChannel.FRAME_HEADROOM, info.size)
                out(frame, AudioChannel.FRAME_HEADROOM, info.size, info.presentationTimeUs)
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    /** Forgets the sound in flight: a new stream to the car starts clean. */
    fun reset() {
        runCatching { codec.flush() }.onFailure { Log.w(TAG, "AAC encoder flush failed", it) }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        private const val TAG = "AareAac"
        private const val INPUT_WAIT_US = 10_000L
        /** Near-transparent AAC-LC at 48 kHz; the USB link has room to spare (PCM is ~1.5 Mbit/s). */
        private const val STEREO_BITRATE = 192_000
        private const val MONO_BITRATE = 64_000
    }
}
