package net.prototux.aare.host.templates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeGlyphTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private val white = argb(255, 255, 255, 255)
    private val nearWhite = argb(255, 250, 248, 252)
    private val transparent = argb(0, 255, 255, 255)

    @Test
    fun aWhiteGlyphOnTransparentIsAMonochromeGlyph() {
        val pixels = IntArray(100) { if (it % 2 == 0) white else transparent }
        assertTrue(looksLikeMonochromeGlyph(pixels))
    }

    @Test
    fun aNearWhiteGlyphStillCounts() {
        val pixels = IntArray(32) { nearWhite }
        assertTrue(looksLikeMonochromeGlyph(pixels))
    }

    @Test
    fun aColouredLogoIsNotAMonochromeGlyph() {
        val red = argb(255, 220, 20, 20)
        val pixels = IntArray(32) { red }
        assertFalse(looksLikeMonochromeGlyph(pixels))
    }

    @Test
    fun aPhotoLikeMixOfColoursIsNotAMonochromeGlyph() {
        val colours = intArrayOf(
            argb(255, 180, 90, 40), argb(255, 30, 60, 120), argb(255, 90, 140, 60),
            argb(255, 200, 180, 60), argb(255, 60, 30, 10), argb(255, 10, 90, 160),
        )
        val pixels = IntArray(60) { colours[it % colours.size] }
        assertFalse(looksLikeMonochromeGlyph(pixels))
    }

    @Test
    fun mostlyTransparentWithOnlyAFewOpaquePixelsIsNotAMonochromeGlyph() {
        // Fewer opaque pixels than the minimum: a stray antialiasing fringe shouldn't tint a photo.
        val pixels = IntArray(200) { if (it < 10) white else transparent }
        assertFalse(looksLikeMonochromeGlyph(pixels))
    }

    @Test
    fun aMostlyWhiteGlyphWithAFewColouredAccentPixelsStillCounts() {
        val red = argb(255, 220, 20, 20)
        val pixels = IntArray(100) { if (it < 5) red else white }
        assertTrue(looksLikeMonochromeGlyph(pixels))
    }

    @Test
    fun halfColouredHalfWhiteIsNotAMonochromeGlyph() {
        val red = argb(255, 220, 20, 20)
        val pixels = IntArray(100) { if (it < 50) red else white }
        assertFalse(looksLikeMonochromeGlyph(pixels))
    }
}
