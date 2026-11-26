package net.prototux.aare.car

import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import org.junit.Assert.assertEquals
import org.junit.Test

class FpsOfTest {
    private fun config() = VideoConfiguration.newBuilder().setCodecResolution(VideoCodecResolutionType.VIDEO_1280x720)

    @Test
    fun aCarThatDoesNotStateARateGets30() = assertEquals(30, CarSession.fpsOf(config().build()))

    @Test
    fun statedRatesAreKept() {
        assertEquals(60, CarSession.fpsOf(config().setFrameRate(VideoFrameRateType.VIDEO_FPS_60).build()))
        assertEquals(30, CarSession.fpsOf(config().setFrameRate(VideoFrameRateType.VIDEO_FPS_30).build()))
    }
}
