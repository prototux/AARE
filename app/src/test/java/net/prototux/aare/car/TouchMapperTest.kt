package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchMapperTest {
    @Test
    fun zeroSizedHyundaiPanelUsesVisibleVideoCoordinates() {
        val mapper = TouchMapper(0, 0, 1920, 1080, 0, 360)
        assertEquals(1920, mapper.panelWidth)
        assertEquals(720, mapper.panelHeight)
        // Actual first touch from the Kona log. Previously (1384320, 261540).
        assertEquals(721f, mapper.mapX(721), 0.001f)
        assertEquals(543f, mapper.mapY(363), 0.001f)
        assertEquals(180f, mapper.mapY(0), 0.001f)
        assertEquals(899f, mapper.mapY(719), 0.001f)
    }

    @Test
    fun validPanelStillScalesIntoVisibleVideoAndOffsetsMargins() {
        val mapper = TouchMapper(1280, 480, 1920, 1080, 0, 360)
        assertEquals(960f, mapper.mapX(640), 0.001f)
        assertEquals(540f, mapper.mapY(240), 0.001f)
    }

    @Test
    fun dimensionsFallBackIndependentlyWithoutDiscardingValidAxis() {
        val missingWidth = TouchMapper(-1, 480, 1920, 1080, 0, 360)
        assertEquals(721f, missingWidth.mapX(721), 0.001f)
        assertEquals(540f, missingWidth.mapY(240), 0.001f)
        val missingHeight = TouchMapper(1280, 0, 1920, 1080, 0, 360)
        assertEquals(960f, missingHeight.mapX(640), 0.001f)
        assertEquals(543f, missingHeight.mapY(363), 0.001f)
    }

    @Test
    fun fallbackWithHorizontalAndVerticalMargins() {
        val mapper = TouchMapper(0, 0, 1280, 720, 256, 120)
        assertEquals(128f, mapper.mapX(0), 0.001f)
        assertEquals(60f, mapper.mapY(0), 0.001f)
        assertEquals(640f, mapper.mapX(512), 0.001f)
        assertEquals(360f, mapper.mapY(300), 0.001f)
    }
}
