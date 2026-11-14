package net.prototux.aare.car

import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService.TouchPad
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * How far a finger travels on the car's touchpad for one focus step, in touchpad units, from the
 * touchpad's descriptor: a sixth of the pad, at least 4 mm,
 * scaled by the car's sensitivity. The second step of one swipe needs [multiMove], 4 mm more, so a
 * swipe meant for one item doesn't run on to the next.
 */
internal data class TouchpadThresholds(val move: Int, val multiMove: Int) {
    companion object {
        /** Default values for a touchpad without its size. */
        val DEFAULT = TouchpadThresholds(48, 64)
        private const val BASE_FRACTION = 6
        private const val MIN_SIZE_MM = 4f
        private const val MULTIMOVE_PENALTY_MM = 4f

        fun of(pad: TouchPad?): TouchpadThresholds {
            if (pad == null || pad.width == 0 || pad.height == 0) return DEFAULT
            val w = pad.width
            val h = pad.height
            // Without a physical size, one unit stands for a millimetre.
            val physical = pad.physicalWidth != 0 && pad.physicalHeight != 0
            val pw = if (physical) pad.physicalWidth else w
            val ph = if (physical) pad.physicalHeight else h
            val scale = sensitivityScale(pad)
            // Units per millimetre in whole units here.
            val base = if (w / BASE_FRACTION > h / BASE_FRACTION) maxOf(w / BASE_FRACTION, (MIN_SIZE_MM * (w / pw)).toInt())
                else maxOf(h / BASE_FRACTION, (MIN_SIZE_MM * (h / ph)).toInt())
            val move = (base * scale).toInt()
            val unitsPerMm = if (w > h) w.toFloat() / pw else h.toFloat() / ph
            return TouchpadThresholds(move, move + ((MULTIMOVE_PENALTY_MM * unitsPerMm).toInt() * scale).toInt())
        }

        /** Sensitivity 5 (or none) is neutral; each step up shortens the swipe by a tenth, each step down lengthens it. */
        fun sensitivityScale(pad: TouchPad): Float {
            val s = if (pad.hasSensitivity()) pad.sensitivity else 0
            return if (s == 0) 1f else 1f + (s - 5f) * -0.5f / 5f
        }
    }
}

/**
 * A touchpad that drives the car screen's focus (its descriptor sets ui_navigation):
 * a swipe moves the focus one item each time the finger travels a step
 * ([TouchpadThresholds]) from where the last step was measured, in the direction it went furthest;
 * the next step is measured from one step further along, so a long swipe walks on item by item.
 * Turning back by more than a third of a step starts over from there. Taps don't select: the car
 * sends a separate select (D-pad centre) for the pad's press.
 *
 * [moveFocus] dispatches the D-pad key of a direction to the car screen and says whether the focus
 * moved (or showed). Main thread.
 */
internal class TouchpadNavigator(
    private val thresholds: TouchpadThresholds,
    private val moveFocus: (keyCode: Int) -> Boolean,
) {
    private var tracking = false
    private var originX = 0f
    private var originY = 0f
    // The furthest the finger went from the origin, each way, since the origin was set.
    private var maxX = 0f
    private var minX = 0f
    private var maxY = 0f
    private var minY = 0f
    /** A step was taken in this swipe; the second one needs the longer [TouchpadThresholds.multiMove]. */
    private var moved = false
    private var multiMove = false

    /** One touchpad event ([MotionEvent] action, first pointer's position). True when the focus moved. */
    fun onEvent(action: Int, x: Float, y: Float): Boolean {
        if (action == MotionEvent.ACTION_DOWN || !tracking) {
            setOrigin(x, y)
            moved = false
            multiMove = false
            tracking = true
        }
        if (action != MotionEvent.ACTION_MOVE) {
            if (action == MotionEvent.ACTION_UP) tracking = false
            return false
        }
        val dx = x - originX
        val dy = y - originY
        val step = if (!moved || multiMove) thresholds.move else thresholds.multiMove
        val slack = (step / 3f).toInt().toFloat()
        if ((maxX > slack && maxX - dx > slack) || (minX < -slack && dx - minX > slack) ||
            (maxY > slack && maxY - dy > slack) || (minY < -slack && dy - minY > slack)) {
            setOrigin(x, y)
            return false
        }
        if (dx > maxX) maxX = dx else if (dx < minX) minX = dx
        if (dy > maxY) maxY = dy else if (dy < minY) minY = dy
        if (abs(dx) < step && abs(dy) < step) return false

        val horizontal = abs(dx) > abs(dy)
        val key = when {
            horizontal -> if (dx >= 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            else -> if (dy >= 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        }
        if (!moveFocus(key)) {
            // Nothing that way: measure the next swipe from here.
            setOrigin(x, y)
            return false
        }
        if (horizontal) setOrigin(originX + step * sign(dx), y) else setOrigin(x, originY + step * sign(dy))
        if (moved) multiMove = true
        moved = true
        return true
    }

    private fun setOrigin(x: Float, y: Float) {
        originX = x
        originY = y
        maxX = 0f; minX = 0f; maxY = 0f; minY = 0f
    }
}
