package net.prototux.aare.car

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Car screens whose pixels aren't square. A head unit says so with the video configuration's
 * pixel_aspect_ratio_e4 (a pixel's width over its height, times 10^4); a picture drawn for square
 * pixels then looks stretched on it, circles as ovals. The UI is laid out on a
 * logical screen that is wider (pixels wider than tall) or taller (narrower pixels) by that factor,
 * at the same density, and squeezes it into the video frame, so the car's pixels stretch it back.
 */
object PixelAspect {
    /** Factors outside this range are a head unit's bug, not a screen: ignored. */
    private const val MIN = 0.5f
    private const val MAX = 2f

    /**
     * The factor to lay the UI out with. A vehicles.json [override] wins (the built-in file has
     * one for some older head units, protocol 1.1 and before, that can't report it). What the head
     * unit reports, from protocol 1.2 on (or without a make), only with [useReported]: off unless
     * asked for, so no head unit that looks right today changes.
     */
    fun factor(override: Float?, reportedE4: Int?, protocol: Pair<Int, Int>?, make: String, useReported: Boolean): Float {
        val reportsIt = protocol == null || VehicleMatch.compareVersions(protocol, 1 to 2) >= 0 || make.isEmpty()
        val f = override ?: reportedE4?.takeIf { useReported && reportsIt && it > 0 }?.let { it / 10_000f } ?: return 1f
        return if (f in MIN..MAX) f else 1f
    }

    /**
     * The logical size a [width] x [height] pixel area stands for: wider by [factor] when the pixels
     * are wider than tall, taller when they are narrower. The density stays the same.
     */
    fun logicalSize(width: Int, height: Int, factor: Float): Pair<Int, Int> = when {
        factor > 1f -> (width * factor).roundToInt() to height
        factor < 1f -> width to (height / factor).roundToInt()
        else -> width to height
    }
}

/**
 * Lays the content out at [PixelAspect.logicalSize] of the space it gets, and squeezes it back into
 * that space. Drawing and touches both go through the scaling layer, so taps still land where the
 * driver sees them.
 */
fun Modifier.pixelAspect(factor: Float): Modifier = if (factor == 1f) this else layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val (lw, lh) = PixelAspect.logicalSize(w, h, factor)
    val p = measurable.measure(Constraints.fixed(lw, lh))
    layout(w, h) {
        p.placeWithLayer(0, 0) {
            scaleX = w / lw.toFloat()
            scaleY = h / lh.toFloat()
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}
