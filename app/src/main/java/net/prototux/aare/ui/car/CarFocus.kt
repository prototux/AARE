package net.prototux.aare.ui.car

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * A clear focus highlight for non-touch head units driven by a rotary controller or D-pad: a thick
 * border on the element's shape while it holds focus, so the driver can see where they are.
 *
 * Place it directly before the element's own `.clickable`, whose focus target it observes (a
 * clickable is focusable by default, so Compose's D-pad traversal already reaches it; this only
 * makes the focus visible). It draws nothing until something is focused, so touch head units,
 * which never move focus, are unaffected; and nothing after a touch, see [CarFocusVisibility].
 *
 * Prefer [carClickable], which pairs this with the click itself so the two can't drift apart.
 */
fun Modifier.carFocusHighlight(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val shown = focused && CarFocusVisibility.keyInput
    onFocusChanged { focused = it.isFocused }
        .border(width = if (shown) 3.dp else 0.dp, color = if (shown) color else Color.Transparent, shape = shape)
}

/**
 * The knob-friendly way to make a car-screen element clickable: [carFocusHighlight] plus the click
 * itself, in one call, so a new clickable can't forget the highlight (and an old one can't lose it
 * in a refactor). [onClick] null means this element isn't clickable right now (e.g. a card with no
 * action) and is a no-op, matching the common null-check-then-click pattern it replaces.
 */
fun Modifier.carClickable(
    shape: Shape,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: (() -> Unit)?,
): Modifier = when {
    onClick == null -> this
    onLongClick != null -> carFocusHighlight(shape)
        .combinedClickable(enabled = enabled, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
    else -> carFocusHighlight(shape).clickable(enabled = enabled, onClick = onClick)
}

/**
 * The knob-friendly way to make a car-screen value adjustable, for what a touch changes with a
 * gesture (a divider's position, a seek bar): focusable, with [carFocusHighlight]'s highlight.
 * Select (the knob's press, D-pad centre) starts adjusting, shown by a thicker highlight; then
 * each knob detent, or each D-pad press along the control (left/right, up/down when [vertical]),
 * calls [onStep] with +1 or -1 (clockwise, right, up: +1). Select again, or a D-pad press across
 * the control, which moves the focus on, stops adjusting.
 *
 * Adjusting is a mode rather than the default because the knob's turn and the D-pad are also what
 * move the focus: a control that took them whenever it was focused would trap the focus on it.
 * Keep the touch gesture next to it (e.g. a `pointerInput` drag): this only adds the key path.
 */
fun Modifier.carAdjustable(shape: Shape, vertical: Boolean = false, onStep: (Int) -> Unit): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val shown = focused && CarFocusVisibility.keyInput
    onFocusChanged { focused = it.isFocused; if (!it.isFocused) adjusting = false }
        .border(width = if (!shown) 0.dp else if (adjusting) 6.dp else 3.dp, color = if (shown) color else Color.Transparent, shape = shape)
        .onKeyEvent { event ->
            val down = event.type == KeyEventType.KeyDown
            if (!down && event.type != KeyEventType.KeyUp) return@onKeyEvent false
            val key = carAdjustKey(event.nativeKeyEvent.keyCode, event.isShiftPressed, vertical, adjusting)
            // Act on the press; the release of a key the press used goes with it.
            if (down) {
                adjusting = key.adjusting
                if (key.step != 0) onStep(key.step)
            }
            key.consumed
        }
        .focusable()
}

/** What one key does to a [carAdjustable] control: whether it's adjusting after it, the step, whether the key is used up. */
internal data class CarAdjustKey(val adjusting: Boolean, val step: Int = 0, val consumed: Boolean = true)

/**
 * The key handling of [carAdjustable], as a pure function of the Android [keyCode] (the knob's
 * turn comes in as Tab, Shift+Tab turning back) and whether the control was [adjusting].
 */
internal fun carAdjustKey(keyCode: Int, shift: Boolean, vertical: Boolean, adjusting: Boolean): CarAdjustKey {
    val select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    if (select) return CarAdjustKey(adjusting = !adjusting)
    // Not adjusting: every other key goes on to move the focus as usual.
    if (!adjusting) return CarAdjustKey(adjusting = false, consumed = false)
    val step = when (keyCode) {
        KeyEvent.KEYCODE_TAB -> if (shift) -1 else 1
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (vertical) null else 1
        KeyEvent.KEYCODE_DPAD_LEFT -> if (vertical) null else -1
        KeyEvent.KEYCODE_DPAD_UP -> if (vertical) 1 else null
        KeyEvent.KEYCODE_DPAD_DOWN -> if (vertical) -1 else null
        else -> 0
    }
    return when (step) {
        // A D-pad press across the control: stop adjusting and let it move the focus on.
        null -> CarAdjustKey(adjusting = false, consumed = false)
        // Any other key (Back, a media key): not ours, and the adjusting goes on.
        0 -> CarAdjustKey(adjusting = true, consumed = false)
        else -> CarAdjustKey(adjusting = true, step = step)
    }
}

/**
 * Whether the car's last input was a navigation key rather than a touch (main thread). Car touches
 * are injected straight into the window, so they never put it back in touch mode once a knob took
 * it out: on a unit with both, the highlight would otherwise stay up and follow the taps.
 */
object CarFocusVisibility {
    var keyInput by mutableStateOf(false)

    /**
     * Whether the car has a rotary knob (it sent a turn this session). Only then do touch-only
     * screens get a cursor, see [CursorPane]: touch screens and D-pad-only units keep the plain
     * focus traversal. Reset when the session ends, as the next car may have no knob.
     */
    var rotary by mutableStateOf(false)
}
