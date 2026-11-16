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
 * a SurfaceView follows (its surface is cropped to what its parents show).
 */
class VideoFrame(context: Context) : FrameLayout(context) {
    val surface = SurfaceView(context)
    var ratio = 16f / 9f
        set(v) { if (v > 0f && v != field) { field = v; requestLayout() } }
    var fit = VideoFit.FIT
        set(v) { if (v != field) { field = v; requestLayout() } }

    init {
        clipChildren = true
        addView(surface)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (sw, sh) = surfaceSize(w, h, ratio, fit)
        surface.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = (width - surface.measuredWidth) / 2
        val top = (height - surface.measuredHeight) / 2
        surface.layout(left, top, left + surface.measuredWidth, top + surface.measuredHeight)
    }
}

/** The surface's size in a [w] by [h] frame, for a picture [ratio] wide per unit high. */
internal fun surfaceSize(w: Int, h: Int, ratio: Float, fit: VideoFit): Pair<Int, Int> {
    if (w <= 0 || h <= 0 || ratio <= 0f || fit == VideoFit.STRETCH) return w to h
    val wider = ratio > w.toFloat() / h
    // Fit matches the frame on the side the picture overflows; zoom on the other side.
    return if (wider == (fit == VideoFit.FIT)) w to (w / ratio).roundToInt() else (h * ratio).roundToInt() to h
}
