package net.prototux.aare.car

import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Turns the head unit's touch reports into Android [MotionEvent]s in projection coordinates.
 *
 * The touch panel reports in its own resolution, which covers the visible part of the video
 * (the video minus its margins); we scale and offset into the full video frame.
 */
class TouchMapper(
    private val touchWidth: Int,
    private val touchHeight: Int,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val marginX: Int,
    private val marginY: Int,
) {
    private var downTime = 0L
    // Some factory head units (Hyundai GEN5 Wide) advertise a 0x0 touchscreen.
    // Treat each nonpositive dimension as unspecified and use the visible video size.
    // Using 1 as the denominator instead multiplies every touch far outside the display.
    val panelWidth = touchWidth.takeIf { it > 0 } ?: (videoWidth - marginX).coerceAtLeast(1)
    val panelHeight = touchHeight.takeIf { it > 0 } ?: (videoHeight - marginY).coerceAtLeast(1)
    private val scaleX = (videoWidth - marginX).toFloat() / panelWidth
    private val scaleY = (videoHeight - marginY).toFloat() / panelHeight

    internal fun mapX(x: Int): Float = x * scaleX + marginX / 2f
    internal fun mapY(y: Int): Float = y * scaleY + marginY / 2f

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
                x = mapX(p.x)
                y = mapY(p.y)
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(downTime, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
