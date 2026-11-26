package net.prototux.aare.media

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import net.prototux.aare.Settings
import net.prototux.aare.audio.Spectrum

/**
 * Spectrum bars rising from the bottom, rounded or square, colored by height (one gradient over
 * the whole height) or in the accent color, with a peak mark over each bar. Bar height is linear
 * in dB from [Spectrum.FLOOR_DB] (empty) to 0 dBFS (full).
 */
object SpectrumVisual : NowPlayingVisual {
    override val id = Settings.VISUAL_SPECTRUM

    @Composable
    override fun Content(frames: SpectrumFrames, stereo: Boolean, style: VisualStyle, modifier: Modifier) {
        val cache = remember { BrushCache() }
        // Its own layer: a new frame re-records only the bars, not the screen around them.
        Canvas(modifier.graphicsLayer()) {
            @Suppress("UNUSED_EXPRESSION") frames.tick // redraw on every frame (draw phase only)
            // Whole pixels, and not too thin: the car gets this as compressed video, and a line a
            // pixel or two high loses its color (the encoder keeps color at half resolution).
            val peak = PEAK.roundToPx().toFloat()
            val brush = style.stops?.let { cache.brush(size.height, it) }
            if (stereo && frames.channels == 2) {
                val half = size.width / 2
                // Left channel: low at the left edge, high at the middle. Right: mirrored.
                drawBars(frames.bars[0], frames.caps[0], 0f, half, mirrored = false, peak, brush, style)
                drawBars(frames.bars[1], frames.caps[1], half, half, mirrored = true, peak, brush, style)
            } else {
                drawBars(frames.bars[0], frames.caps[0], 0f, size.width, mirrored = false, peak, brush, style)
            }
        }
    }

    private fun DrawScope.drawBars(
        bars: FloatArray, caps: FloatArray, left: Float, width: Float, mirrored: Boolean,
        peak: Float, brush: Brush?, style: VisualStyle,
    ) {
        val n = bars.size
        val slot = width / n
        val barGap = (slot * BAR_GAP_FRACTION).coerceAtLeast(1f)
        val barWidth = slot - barGap
        val bottom = size.height
        val a = style.alpha
        val radius = if (style.rounded) CornerRadius(barWidth / 2) else CornerRadius.Zero
        val peakRadius = if (style.rounded) CornerRadius(peak / 2) else CornerRadius.Zero
        for (i in 0 until n) {
            val x = left + (if (mirrored) n - 1 - i else i) * slot + barGap / 2
            val h = Math.round(fraction(bars[i]) * size.height).toFloat()
            if (h > 0f) {
                if (brush != null) drawRoundRect(brush, Offset(x, bottom - h), Size(barWidth, h), radius, alpha = a)
                else drawRoundRect(style.accent, Offset(x, bottom - h), Size(barWidth, h), radius, alpha = a)
            }
            if (style.peaks) {
                val top = Math.round(fraction(caps[i]) * size.height).toFloat()
                if (top > h + peak) {
                    val color = if (style.stops != null) colorAt(top / size.height, style.stops) else style.dim
                    drawRoundRect(color, Offset(x, bottom - top), Size(barWidth, peak), peakRadius, alpha = a)
                }
            }
        }
    }

    /** 0 (floor) to 1 (0 dBFS), linear in dB. */
    private fun fraction(db: Float): Float = ((db - Spectrum.FLOOR_DB) / -Spectrum.FLOOR_DB).coerceIn(0f, 1f)

    /** The gradient, made again only when the height or the colors change. */
    private class BrushCache {
        private var brush: Brush? = null
        private var height = -1f
        private var stops: List<Pair<Float, Color>>? = null

        /** [stops] over [height], bottom to top. */
        fun brush(height: Float, stops: List<Pair<Float, Color>>): Brush {
            val made = brush
            if (height == this.height && stops == this.stops && made != null) return made
            this.height = height
            this.stops = stops
            // verticalGradient runs top (0) to bottom (1); the stops count from the bottom.
            val flipped = stops.map { (f, c) -> (1f - f) to c }.sortedBy { it.first }.toTypedArray()
            return Brush.verticalGradient(*flipped, startY = 0f, endY = height).also { brush = it }
        }
    }

    /** The color at [f] of the height (0 bottom, 1 top), blended between [stops]. */
    private fun colorAt(f: Float, stops: List<Pair<Float, Color>>): Color {
        if (f <= stops.first().first) return stops.first().second
        for (k in 1 until stops.size) {
            val (f1, c1) = stops[k]
            if (f <= f1) {
                val (f0, c0) = stops[k - 1]
                return if (f1 == f0) c1 else lerp(c0, c1, (f - f0) / (f1 - f0))
            }
        }
        return stops.last().second
    }

    private val PEAK = 4.dp
    private const val BAR_GAP_FRACTION = 0.22f
}
