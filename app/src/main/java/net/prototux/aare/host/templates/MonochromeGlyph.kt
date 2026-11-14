package net.prototux.aare.host.templates

/**
 * Whether [pixels] (packed ARGB, one int per pixel, as [android.graphics.Bitmap.getPixels] or
 * [androidx.compose.ui.graphics.ImageBitmap.readPixels] hand out) look like a monochrome glyph:
 * a white (or near-white) icon drawn for Android Auto's own dark screen, rather than a coloured
 * logo, a photo or album art. Car apps draw most custom icons this way, so on a light theme the
 * icon is invisible unless it's tinted with the current content colour.
 *
 * Almost every opaque pixel must be both light (luminance) and unsaturated (chroma): a logo with
 * one accent colour on a mostly-white glyph still counts as coloured and is left alone. A bitmap
 * with too few opaque pixels (mostly transparent, or just noise) is never called a glyph either,
 * so a tiny or empty icon doesn't get tinted on a guess.
 */
internal fun looksLikeMonochromeGlyph(
    pixels: IntArray,
    minOpaqueAlpha: Float = 0.3f,
    minLuminance: Float = 0.8f,
    maxChroma: Float = 0.1f,
    minLightFraction: Float = 0.9f,
    minOpaquePixels: Int = 16,
): Boolean {
    var opaque = 0
    var lightAndUnsaturated = 0
    for (pixel in pixels) {
        val alpha = (pixel ushr 24 and 0xFF) / 255f
        if (alpha < minOpaqueAlpha) continue
        opaque++
        val r = (pixel ushr 16 and 0xFF) / 255f
        val g = (pixel ushr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        if (luminance > minLuminance && chroma < maxChroma) lightAndUnsaturated++
    }
    if (opaque < minOpaquePixels) return false
    return lightAndUnsaturated >= opaque * minLightFraction
}
