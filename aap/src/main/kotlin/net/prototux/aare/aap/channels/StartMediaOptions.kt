package net.prototux.aare.aap.channels

import aap_protobuf.service.media.shared.message.MediaOptionsOuterClass.MediaOptions
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.shared.DurationOuterClass.Duration

/**
 * The media options a START carries (field 4), filled with its built-in
 * settings (its server can change them; what it sends in the field is not known). They tune the
 * head unit's buffering, audio/video sync and statistics reports. Only sent to head units that get
 * a drop overflow stream (see [MediaSinkChannel.dropsOverflow]).
 */
internal object StartMediaOptions {
    /** An audio stream's, for a head unit of protocol [headUnitMajor] (5 and later). */
    fun audio(stream: AudioStreamType, headUnitMajor: Int): MediaOptions {
        val options = MediaOptions.newBuilder()
            .setAudioBufferLimit(ms(if (stream == AudioStreamType.AUDIO_STREAM_MEDIA) 300 else 400))
            .setEnableSync(false)
            .setMaxLagTolerance(ms(20))
            .setSyncInterval(ms(1_000))
            .setMaxAudioDelayAllowed(ms(500))
            .setMaxVideoDelayAllowed(ms(500))
            .setAverageLagWeight(4)
            .setEnableMediaStats(false)
        // Statistics only from 6.0 head units: every 5 s, every 0.5 s for the navigation voice.
        if (headUnitMajor >= 6) {
            options.setEnableMediaStats(true)
                .setMediaStatsReportInterval(ms(if (stream == AudioStreamType.AUDIO_STREAM_GUIDANCE) 500 else 5_000))
        }
        return options.build()
    }

    /** The video stream's (head units 6.0 and later). */
    fun video(): MediaOptions = MediaOptions.newBuilder()
        .setEnableMediaStats(true)
        .setMediaStatsReportInterval(ms(5_000))
        .setEnableVideoTimingController(true)
        .setVideoTimingMinDelay(ms(9))
        .setVideoTimingMaxDelay(ms(17))
        .build()

    /** Fields that are 0 are left out, as a proto3 Duration leaves them. */
    private fun ms(millis: Long): Duration = Duration.newBuilder().apply {
        if (millis / 1_000 != 0L) seconds = millis / 1_000
        val nanos = (millis % 1_000 * 1_000_000).toInt()
        if (nanos != 0) this.nanos = nanos
    }.build()
}
