package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Ford SYNC 4 (Mustang Mach-E, #74): a 1920x1080 frame with 878 px of width margin (visible
     * 1042x1080), the unit declares a 1200x1244 panel but reports touches in visible-area pixels.
     * The declared panel is strictly larger than the visible area and the same shape, so the mapper
     * recognises the visible-pixel case and maps 1:1 + margin — the bottom rail (y up to 1062) and
     * the far right edge become reachable, which scaling by 1200x1244 would put ~13% short.
     */
    @Test
    fun fordVisiblePixelPanelMapsOneToOneIntoVisibleArea() {
        val m = mapper(1200, 1244, 1920, 1080, 878, 0)
        assertEquals(1042, m.panelWidth)
        assertEquals(1080, m.panelHeight)
        assertEquals(439f, m.mapX(0), 0.001f)        // left edge sits past the left margin
        assertEquals(1462f, m.mapX(1023), 0.001f)    // far-right tap: 1023 + 439
        assertEquals(0f, m.mapY(0), 0.001f)
        assertEquals(1062f, m.mapY(1062), 0.001f)    // bottom rail is reachable
    }

    /** A panel genuinely larger than the visible area, but a different shape, is scaled down (not visible-pixels). */
    @Test
    fun honestLargerPanelOfDifferentShapeIsDownscaled() {
        val m = mapper(2000, 2000, 1920, 1080, 878, 0)
        assertEquals(2000, m.panelWidth)             // treated as a real panel, not visible pixels
        assertEquals(960f, m.mapX(1000), 0.001f)     // 1000 * 1042/2000 + 439
        assertEquals(540f, m.mapY(1000), 0.001f)     // 1000 * 1080/2000
    }

    /** A coordinate beyond the declared panel proves it is too small: that axis falls back to visible. */
    @Test
    fun underDeclaredPanelLatchesToVisibleArea() {
        val m = mapper(100, 100, 1000, 1000, 0, 0)
        assertEquals(100, m.panelWidth)
        m.observe(500, 50)                           // x past the 100-px panel, y within it
        assertEquals(1000, m.panelWidth)             // x axis latched to the visible area
        assertEquals(100, m.panelHeight)             // y axis untouched
        assertEquals(500f, m.mapX(500), 0.001f)
    }

    /** Mapped output is clamped to the visible area, so a stray report never dispatches off-surface. */
    @Test
    fun outputIsClampedToTheVisibleArea() {
        val m = mapper(1000, 1000, 1000, 1000, 0, 0)
        assertEquals(1000f, m.mapX(1200), 0.001f)    // beyond the right edge clamps to it
        assertTrue(m.mapX(-20) >= 0f)                // never negative
    }
}
