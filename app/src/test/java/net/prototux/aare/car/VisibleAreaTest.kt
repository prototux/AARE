package net.prototux.aare.car

import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleAreaTest {
    // Ford SYNC 4 in a Mustang Mach-E (#74): a 1920x1080 frame of which a 1042 px wide part shows.
    private fun config(ui: UiConfig? = null) = VideoConfiguration.newBuilder().setWidthMargin(878).setHeightMargin(0)
        .apply { if (ui != null) setUiConfig(ui) }.build()

    @Test
    fun marginsAreSplitEvenlyWithoutUiMargins() {
        assertEquals(VisibleArea(439, 0, 1042, 1080), VisibleArea.of(config(), 1920, 1080))
    }

    @Test
    fun explicitUiMarginsPlaceTheAreaOffCentre() {
        val ui = UiConfig.newBuilder().setMargins(Insets.newBuilder().setRight(878)).build()
        assertEquals(VisibleArea(0, 0, 1042, 1080), VisibleArea.of(config(ui), 1920, 1080))
    }

    @Test
    fun allZeroUiMarginsDoNotUndoTheWidthMargin() {
        val ui = UiConfig.newBuilder().setMargins(Insets.getDefaultInstance()).build()
        assertEquals(VisibleArea(439, 0, 1042, 1080), VisibleArea.of(config(ui), 1920, 1080))
    }

    @Test
    fun marginsLargerThanTheFrameLeaveAPixel() {
        val ui = UiConfig.newBuilder().setMargins(Insets.newBuilder().setLeft(1500).setRight(1500)).build()
        val area = VisibleArea.of(config(ui), 1920, 1080)
        assertEquals(1500, area.left)
        assertEquals(1, area.width)
    }
}
