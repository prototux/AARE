package net.prototux.aare.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import net.prototux.aare.DiagLog
import net.prototux.aare.aap.pcm.PcmFormat
import java.nio.ByteBuffer

/**
 * AARE's video player for the car screen (media3 ExoPlayer).
 *
 * Its sound is converted to the car's media format inside the player (channel mix, then
 * resampling) and tapped there ([CarPlayerAudio]): the car plays it on its media channel while
 * the phone's own output is muted, so no screen capture is needed and the phone stays quiet. The
 * phone output keeps running (muted) because it is the player's clock: video frames follow it,
 * so picture and sound stay in step. What's tapped is ahead of the phone's output by its buffer,
 * which the car's own playback latency roughly cancels out; see the report for measuring it.
 */
@OptIn(UnstableApi::class)
object CarVideoPlayer {
    private const val TAG = "AareVideo"
    /** The car's media format when no car has said yet (the usual Android Auto media channel). */
    val DEFAULT_FORMAT = PcmFormat(48_000, 2)

    fun create(context: Context): ExoPlayer {
        val format = CarPlayerAudio.mediaFormat ?: DEFAULT_FORMAT
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(carProcessors(format))
                    .build()
        }
        val player = ExoPlayer.Builder(context, renderers).build()
        // A movie, with audio focus: other apps' music pauses while it plays, and it pauses for a call.
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.addListener(object : Player.Listener {
            // The car shouldn't keep playing what was buffered before a pause.
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) CarPlayerAudio.flush() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "playback", error)
                DiagLog.add("car video: playback error ${error.errorCodeName}: ${error.message}")
            }
        })
        return player
    }

    /** Down/up-mix to the car's channels, resample to its rate, then copy for the car. */
    private fun carProcessors(format: PcmFormat): Array<AudioProcessor> {
        val mix = ChannelMixingAudioProcessor()
        // Every input count needs a matrix, or the player refuses the track.
        for (inCh in 1..8) mix.putChannelMixingMatrix(ChannelMixingMatrix(inCh, format.channels, downmixCoefficients(inCh, format.channels)))
        val rate = SonicAudioProcessor().apply { setOutputSampleRateHz(format.rate) }
        val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
            private var usable = false
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                usable = sampleRateHz == format.rate && channelCount == format.channels && encoding == C.ENCODING_PCM_16BIT
                if (!usable) DiagLog.add("car video: sound is $sampleRateHz Hz x $channelCount (encoding $encoding), not the car's ${format.rate} Hz x ${format.channels}: not sent to the car")
                CarPlayerAudio.flush()
            }

            override fun handleBuffer(buffer: ByteBuffer) {
                if (!usable || !buffer.hasRemaining()) return
                val pcm = ByteArray(buffer.remaining())
                buffer.duplicate().get(pcm)
                CarPlayerAudio.push(pcm)
            }
        })
        return arrayOf(mix, rate, tee)
    }
}
