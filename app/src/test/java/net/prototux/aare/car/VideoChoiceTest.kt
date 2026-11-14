package net.prototux.aare.car

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Choosing among the head unit's video configurations by the size the car shows. */
class VideoChoiceTest {
    private val h264 = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP

    private fun config(res: VideoCodecResolutionType, widthMargin: Int = 0, heightMargin: Int = 0) = VideoConfiguration.newBuilder()
        .setCodecResolution(res).setWidthMargin(widthMargin).setHeightMargin(heightMargin)
        .setFrameRate(VideoFrameRateType.VIDEO_FPS_30).setVideoCodecType(h264).build()

    @Test fun aWideScreenOffered1080WithMarginsFitsA720Limit() {
        val configs = listOf(config(VideoCodecResolutionType.VIDEO_1280x720), config(VideoCodecResolutionType.VIDEO_1920x1080, heightMargin = 360))
        assertEquals(1, CarSession.chooseVideo(configs, emptyList(), maxHeight = 720, prefer60 = false, codec = h264))
    }

    @Test fun withoutMarginsTheLimitStillApplies() {
        val configs = listOf(config(VideoCodecResolutionType.VIDEO_1280x720), config(VideoCodecResolutionType.VIDEO_1920x1080))
        assertEquals(0, CarSession.chooseVideo(configs, emptyList(), maxHeight = 720, prefer60 = false, codec = h264))
    }

    /** vehicles.json videoResolutions (800x480 for Subaru's Harman units). */
    @Test fun listedResolutionsAreTheOnlyOnesPicked() {
        val configs = listOf(config(VideoCodecResolutionType.VIDEO_1280x720), config(VideoCodecResolutionType.VIDEO_800x480))
        assertEquals(1, CarSession.chooseVideo(configs, emptyList(), maxHeight = 1080, prefer60 = false, codec = h264, resolutions = setOf("800x480")))
        assertEquals(0, CarSession.chooseVideo(configs, emptyList(), maxHeight = 1080, prefer60 = false, codec = h264, resolutions = null))
    }

    @Test fun listedResolutionsTheCarDoesntOfferAreIgnored() {
        val configs = listOf(config(VideoCodecResolutionType.VIDEO_1280x720), config(VideoCodecResolutionType.VIDEO_1920x1080))
        assertEquals(0, CarSession.chooseVideo(configs, emptyList(), maxHeight = 720, prefer60 = false, codec = h264, resolutions = setOf("800x480")))
    }

    @Test fun theVisibleSizeIsTheResolutionLessTheMargins() {
        assertEquals(1920 to 720, CarSession.visibleSize(config(VideoCodecResolutionType.VIDEO_1920x1080, heightMargin = 360)))
        assertEquals(1280 to 720, CarSession.visibleSize(config(VideoCodecResolutionType.VIDEO_1280x720)))
    }
}
