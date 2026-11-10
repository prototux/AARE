package net.prototux.aare.car

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import net.prototux.aare.video.VideoCodecKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone↔head-unit video codec matcher: best codec both sides can do. */
class VideoCodecMatchTest {
    private val h264 = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
    private val h265 = MediaCodecType.MEDIA_CODEC_VIDEO_H265
    private val av1 = MediaCodecType.MEDIA_CODEC_VIDEO_AV1

    /** Phone has a hardware encoder for every codec. */
    private val allHardware: (VideoCodecKind) -> Boolean = { true }
    /** Phone can only hardware-encode H.264 (the common case today). */
    private val h264Hardware: (VideoCodecKind) -> Boolean = { it == VideoCodecKind.H264 }

    @Test fun prefersH265WhenBothSidesCanDoIt() =
        assertEquals(VideoCodecKind.H265, CarSession.matchVideoCodec(setOf(h264, h265), forceH264 = false, allHardware))

    @Test fun picksTheMostEfficientWhenAllAvailable() =
        assertEquals(VideoCodecKind.AV1, CarSession.matchVideoCodec(setOf(h264, h265, av1), forceH264 = false, allHardware))

    @Test fun fallsBackToH264WhenThePhoneCantEncodeTheNewerCodec() =
        assertEquals(VideoCodecKind.H264, CarSession.matchVideoCodec(setOf(h264, h265), forceH264 = false, h264Hardware))

    @Test fun h265OnlyCarWithoutPhoneHardwareStillFallsBackToH264() =
        assertEquals(VideoCodecKind.H264, CarSession.matchVideoCodec(setOf(h265), forceH264 = false, h264Hardware))

    @Test fun forceH264OverridesEverything() =
        assertEquals(VideoCodecKind.H264, CarSession.matchVideoCodec(setOf(h264, h265, av1), forceH264 = true, allHardware))

    @Test fun plainH264Car() =
        assertEquals(VideoCodecKind.H264, CarSession.matchVideoCodec(setOf(h264), forceH264 = false, allHardware))
}
