package net.prototux.aare.car

import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** The part of the video frame the car shows, in frame pixels: the frame minus its margins. */
data class VisibleArea(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right get() = left + width
    val bottom get() = top + height

    companion object {
        /**
         * Gearhead's rule: `ui_config.margins` gives each side when the car sends it (a car can
         * show an off-centre part of the frame, e.g. on a portrait screen); otherwise
         * `width_margin`/`height_margin` are split evenly around a centred area. Explicit margins
         * that are all 0 count as absent, so they can't undo `width_margin`.
         */
        fun of(config: VideoConfiguration, frameWidth: Int, frameHeight: Int): VisibleArea {
            val m = config.uiConfig.takeIf { config.hasUiConfig() && it.hasMargins() }?.margins
                ?.takeIf { it.left + it.right + it.top + it.bottom > 0 }
            val left = m?.left ?: (config.widthMargin / 2)
            val right = m?.right ?: (config.widthMargin - config.widthMargin / 2)
            val top = m?.top ?: (config.heightMargin / 2)
            val bottom = m?.bottom ?: (config.heightMargin - config.heightMargin / 2)
            val x = left.coerceIn(0, frameWidth - 1)
            val y = top.coerceIn(0, frameHeight - 1)
            return VisibleArea(x, y, (frameWidth - x - right).coerceAtLeast(1), (frameHeight - y - bottom).coerceAtLeast(1))
        }
    }
}

/**
 * Turns the head unit's touch reports into Android [MotionEvent]s in projection coordinates.
 *
 * The touch panel reports in its own resolution, which covers the [area] of the video the car
 * shows; we scale and offset into the full video frame.
 */
class TouchMapper(
    private val touchWidth: Int,
    private val touchHeight: Int,
    private val area: VisibleArea,
) {
    private var downTime = 0L
    private val scaleX = area.width.toFloat() / touchWidth.coerceAtLeast(1)
    private val scaleY = area.height.toFloat() / touchHeight.coerceAtLeast(1)

    fun map(e: TouchEvent): MotionEvent? {
        val n = e.pointerDataCount
        if (n == 0) return null
        val now = SystemClock.uptimeMillis()
        val index = e.actionIndex.coerceIn(0, n - 1)
        val action = when (e.action) {
            PointerAction.ACTION_DOWN -> { downTime = now; MotionEvent.ACTION_DOWN }
            PointerAction.ACTION_UP -> MotionEvent.ACTION_UP
            PointerAction.ACTION_MOVED -> MotionEvent.ACTION_MOVE
            PointerAction.ACTION_POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            PointerAction.ACTION_POINTER_UP -> MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> return null
        }
        if (downTime == 0L) downTime = now
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = e.getPointerData(i).pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            val p = e.getPointerData(i)
            MotionEvent.PointerCoords().apply {
                x = p.x * scaleX + area.left
                y = p.y * scaleY + area.top
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(downTime, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
