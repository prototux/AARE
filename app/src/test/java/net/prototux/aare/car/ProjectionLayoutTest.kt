package net.prototux.aare.car

import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionLayoutTest {
    private fun insets(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): Insets =
        Insets.newBuilder().setLeft(left).setTop(top).setRight(right).setBottom(bottom).build()

    @Test
    fun noMarginsUsesTheWholeFrame() {
        val l = ProjectionLayout.of(1280, 720, 0, 0, null, null)
        assertEquals(ProjectionLayout(1280, 720, 0, 0, 0, 0), l)
        assertEquals(1280, l.contentWidth)
        assertEquals("", l.describe())
    }

    @Test
    fun widthAndHeightMarginsSplitEvenly() {
        val l = ProjectionLayout.of(1280, 720, 81, 40, null, null)
        assertEquals(40, l.marginLeft)
        assertEquals(41, l.marginRight)
        assertEquals(20, l.marginTop)
        assertEquals(20, l.marginBottom)
        assertEquals(1199, l.visibleWidth)
        assertEquals(680, l.visibleHeight)
    }

    @Test
    fun explicitMarginsReplaceTheEvenSplit() {
        // Head units keep width_margin for older phones: it must not count on top of margins.
        val l = ProjectionLayout.of(1920, 1080, 400, 0, insets(left = 400), null)
        assertEquals(400, l.marginLeft)
        assertEquals(0, l.marginRight)
        assertEquals(1520, l.visibleWidth)
        assertEquals(400, l.padLeft)
        assertEquals(0, l.padRight)
    }

    @Test
    fun contentInsetsPadInsideTheVisibleArea() {
        val l = ProjectionLayout.of(1280, 720, 80, 0, null, insets(left = 10, top = 5, right = 20, bottom = 15))
        assertEquals(50, l.padLeft)
        assertEquals(5, l.padTop)
        assertEquals(60, l.padRight)
        assertEquals(15, l.padBottom)
        assertEquals(1280 - 80 - 30, l.contentWidth)
        assertEquals(720 - 20, l.contentHeight)
        // The touch panel still covers the whole visible area, insets included.
        assertEquals(1200, l.visibleWidth)
    }

    @Test
    fun hugeUint32ValuesNeverGoNegative() {
        // 0xFFFFFFFF ("unset" for a C head unit) reads as -1; a big margin can eat the frame.
        val l = ProjectionLayout.of(800, 480, -1, Int.MAX_VALUE, null, insets(left = -1, top = 1_000_000, right = 900, bottom = -5))
        listOf(l.marginLeft, l.marginTop, l.marginRight, l.marginBottom, l.insetLeft, l.insetTop, l.insetRight, l.insetBottom)
            .forEach { assertTrue("side $it", it >= 0) }
        assertTrue(l.visibleWidth >= 1 && l.visibleHeight >= 1)
        assertTrue(l.contentWidth >= 1 && l.contentHeight >= 1)
    }

    @Test
    fun explicitMarginsAreClampedToo() {
        val l = ProjectionLayout.of(800, 480, 0, 0, insets(left = 700, right = 700, top = -1, bottom = 0), null)
        assertEquals(700, l.marginLeft)
        assertEquals(99, l.marginRight)
        assertEquals(1, l.visibleWidth)
        assertEquals(0, l.marginTop)
    }

    @Test
    fun readsTheVideoConfiguration() {
        fun config(ui: UiConfig?) = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_1280x720)
            .setWidthMargin(100)
            .apply { ui?.let { setUiConfig(it) } }
            .build()
        // No ui_config: the even split.
        assertEquals(50, ProjectionLayout.of(config(null), 1280, 720).marginLeft)
        // ui_config without margins: still the even split, with its content insets.
        ProjectionLayout.of(config(UiConfig.newBuilder().setContentInsets(insets(top = 30)).build()), 1280, 720).let {
            assertEquals(50, it.marginLeft)
            assertEquals(30, it.insetTop)
        }
        // Explicit margins win; stable insets stand in for missing content insets.
        ProjectionLayout.of(config(UiConfig.newBuilder().setMargins(insets(right = 120)).setStableContentInsets(insets(bottom = 12)).build()), 1280, 720).let {
            assertEquals(0, it.marginLeft)
            assertEquals(120, it.marginRight)
            assertEquals(12, it.insetBottom)
        }
    }
}
