package net.prototux.aare.player

import android.content.Context
import android.view.SurfaceView
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** How the picture fills the screen. [id] is stored in the settings: never change it once released. */
enum class VideoFit(val id: String) {
    /** The whole picture, with black bars where its shape differs from the screen's. */
    FIT("fit"),
    /** The whole screen, the picture's edges cut off where its shape differs. */
    ZOOM("zoom"),
    /** The whole screen and the whole picture, out of shape. */
    STRETCH("stretch");

    fun next(): VideoFit = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(id: String?): VideoFit = entries.firstOrNull { it.id == id } ?: FIT
    }
}

/**
 * The picture's [SurfaceView], sized in this frame by [fit] and the video's [ratio] (width over
 * height). For [VideoFit.ZOOM] the surface is bigger than the frame and the frame clips it, which
 * a SurfaceView follows (its surface is cropped to what its parents show). [zoomPct] is the zoom
 * as a percentage of the fitted picture, 0 for just enough to fill the frame; [onFillPct] hears
 * what that is for the current frame and picture.
 */
class VideoFrame(context: Context) : FrameLayout(context) {
    val surface = SurfaceView(context)
    var ratio = 16f / 9f
        set(v) { if (v > 0f && v != field) { field = v; requestLayout() } }
    var fit = VideoFit.FIT
        set(v) { if (v != field) { field = v; requestLayout() } }
    var zoomPct = 0
        set(v) { if (v != field) { field = v; requestLayout() } }
    var onFillPct: ((Int) -> Unit)? = null
    private var reportedFill = -1

    init {
        clipChildren = true
        addView(surface)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val fill = fillPct(w, h, ratio)
        if (fill != reportedFill) { reportedFill = fill; post { onFillPct?.invoke(fill) } }
        val (sw, sh) = surfaceSize(w, h, ratio, fit, zoomPct)
        surface.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = (width - surface.measuredWidth) / 2
        val top = (height - surface.measuredHeight) / 2
        surface.layout(left, top, left + surface.measuredWidth, top + surface.measuredHeight)
    }
}

/**
 * The surface's size in a [w] by [h] frame, for a picture [ratio] wide per unit high. A zoom of
 * [zoomPct] percent of the fitted picture; 0 for just enough to fill the frame.
 */
internal fun surfaceSize(w: Int, h: Int, ratio: Float, fit: VideoFit, zoomPct: Int = 0): Pair<Int, Int> {
    if (w <= 0 || h <= 0 || ratio <= 0f || fit == VideoFit.STRETCH) return w to h
    val wider = ratio > w.toFloat() / h
    // Fit matches the frame on the side the picture overflows.
    val (fw, fh) = if (wider) w to (w / ratio).roundToInt() else (h * ratio).roundToInt() to h
    if (fit == VideoFit.FIT) return fw to fh
    if (zoomPct <= 0) return if (wider) (h * ratio).roundToInt() to h else w to (w / ratio).roundToInt()
    return (fw * zoomPct / 100f).roundToInt() to (fh * zoomPct / 100f).roundToInt()
}

/** How much bigger than fitted (in percent) the picture must be to fill the frame. */
internal fun fillPct(w: Int, h: Int, ratio: Float): Int {
    if (w <= 0 || h <= 0 || ratio <= 0f) return 100
    val frame = w.toFloat() / h
    return (100f * maxOf(ratio / frame, frame / ratio)).roundToInt()
}

/** One press of the zoom's -/+ ([steps] of [ZOOM_STEP_PCT]) from [zoomPct] (0: filling, at [fillPct]). */
internal fun nudgeZoom(zoomPct: Int, fillPct: Int, steps: Int): Int {
    val from = if (zoomPct <= 0) fillPct else zoomPct
    // Onto the 5 % grid first, so the steps land on round numbers.
    val snapped = if (steps > 0) (from / ZOOM_STEP_PCT) * ZOOM_STEP_PCT else ((from + ZOOM_STEP_PCT - 1) / ZOOM_STEP_PCT) * ZOOM_STEP_PCT
    return (snapped + steps * ZOOM_STEP_PCT).coerceIn(ZOOM_MIN_PCT, ZOOM_MAX_PCT)
}

internal const val ZOOM_STEP_PCT = 5
internal const val ZOOM_MIN_PCT = 50
internal const val ZOOM_MAX_PCT = 300
