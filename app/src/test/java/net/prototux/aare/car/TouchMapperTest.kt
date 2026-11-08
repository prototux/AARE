package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchMapperTest {
    /** Builds a mapper the way CarSession does, from a video frame and its margins. */
    private fun mapper(touchW: Int, touchH: Int, videoW: Int, videoH: Int, marginX: Int, marginY: Int) =
        TouchMapper(touchW, touchH, ProjectionLayout.of(videoW, videoH, marginX, marginY, null, null))

    @Test
    fun zeroSizedHyundaiPanelUsesVisibleVideoCoordinates() {
        val m = mapper(0, 0, 1920, 1080, 0, 360)
        assertEquals(1920, m.panelWidth)
        assertEquals(720, m.panelHeight)
        // Actual first touch from the Kona log. Previously (1384320, 261540).
        assertEquals(721f, m.mapX(721), 0.001f)
        assertEquals(543f, m.mapY(363), 0.001f)
        assertEquals(180f, m.mapY(0), 0.001f)
        assertEquals(899f, m.mapY(719), 0.001f)
    }

    @Test
    fun validPanelStillScalesIntoVisibleVideoAndOffsetsMargins() {
        val m = mapper(1280, 480, 1920, 1080, 0, 360)
        assertEquals(960f, m.mapX(640), 0.001f)
        assertEquals(540f, m.mapY(240), 0.001f)
    }

    @Test
    fun dimensionsFallBackIndependentlyWithoutDiscardingValidAxis() {
        val missingWidth = mapper(-1, 480, 1920, 1080, 0, 360)
        assertEquals(721f, missingWidth.mapX(721), 0.001f)
        assertEquals(540f, missingWidth.mapY(240), 0.001f)
        val missingHeight = mapper(1280, 0, 1920, 1080, 0, 360)
        assertEquals(960f, missingHeight.mapX(640), 0.001f)
        assertEquals(543f, missingHeight.mapY(363), 0.001f)
    }

    @Test
    fun fallbackWithHorizontalAndVerticalMargins() {
        val m = mapper(0, 0, 1280, 720, 256, 120)
        assertEquals(128f, m.mapX(0), 0.001f)
        assertEquals(60f, m.mapY(0), 0.001f)
        assertEquals(640f, m.mapX(512), 0.001f)
        assertEquals(360f, m.mapY(300), 0.001f)
    }
}
