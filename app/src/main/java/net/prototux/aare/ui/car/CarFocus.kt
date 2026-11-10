package net.prototux.aare.ui.car

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * A clear focus highlight for non-touch head units driven by a rotary controller or D-pad: a thick
 * border on the element's shape while it holds focus, so the driver can see where they are.
 *
 * Place it directly before the element's own `.clickable`, whose focus target it observes (a
 * clickable is focusable by default, so Compose's D-pad traversal already reaches it; this only
 * makes the focus visible). It draws nothing until something is focused, so touch head units,
 * which never move focus, are unaffected; and nothing after a touch, see [CarFocusVisibility].
 */
fun Modifier.carFocusHighlight(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val shown = focused && CarFocusVisibility.keyInput
    onFocusChanged { focused = it.isFocused }
        .border(width = if (shown) 3.dp else 0.dp, color = if (shown) color else Color.Transparent, shape = shape)
}

/**
 * Whether the car's last input was a navigation key rather than a touch (main thread). Car touches
 * are injected straight into the window, so they never put it back in touch mode once a knob took
 * it out: on a unit with both, the highlight would otherwise stay up and follow the taps.
 */
object CarFocusVisibility {
    var keyInput by mutableStateOf(false)
}

/**
 * One pane for the rotary knob, like Android Auto's focus areas: turning the knob (Tab and
 * Shift+Tab, see CarSession) moves focus only among this pane's items, looping from the last back
 * to the first, see [rotaryWrap]. Tilting the knob (the D-pad) still moves between panes.
 */
fun Modifier.rotaryArea(): Modifier = composed {
    // Entering through a FocusRequester lands on the pane's first item (as the screen's starting
    // focus does); the node's own requestFocus doesn't, a group isn't focusable itself.
    val entry = remember { FocusRequester() }
    focusRequester(entry) then RotaryAreaElement(entry)
}

private data class RotaryAreaElement(val entry: FocusRequester) : ModifierNodeElement<RotaryAreaNode>() {
    override fun create() = RotaryAreaNode(entry)
    override fun update(node: RotaryAreaNode) { node.entry = entry }
}

/** A focus group whose focus state reads synchronously, unlike onFocusChanged's (dispatched later). */
internal class RotaryAreaNode(var entry: FocusRequester) : DelegatingNode() {
    val target = delegate(FocusTargetModifierNode(Focusability.Never))
    val hasFocus: Boolean get() = isAttached && target.focusState.hasFocus
    override fun onAttach() { RotaryAreas.areas += this }
    override fun onDetach() { RotaryAreas.areas -= this }
}

/** The car screen's [rotaryArea]s (main thread; one car screen at a time). */
internal object RotaryAreas {
    val areas = mutableListOf<RotaryAreaNode>()
}

/**
 * On the screen's root: a knob turn moves focus as usual, and if that took it out of the pane it
 * was in, puts it back at that pane's other end instead. (Compose's own focus-exit veto can't do
 * this: a vetoed move counts as a failed one, and Compose then wraps around the whole screen.)
 * With no pane focused yet (touch mode), the turn goes through as usual, so the first detent
 * still finds the screen's starting point.
 */
fun Modifier.rotaryWrap(): Modifier = composed {
    val focus = LocalFocusManager.current
    onPreviewKeyEvent { e ->
        if (e.key != Key.Tab) return@onPreviewKeyEvent false
        val area = RotaryAreas.areas.lastOrNull { it.hasFocus } ?: return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) {
            val dir = if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
            val moved = focus.moveFocus(dir)
            // Left the pane (or found nothing): enter it again from the far end, the first item
            // going forward, the last going back.
            if (!moved || !area.hasFocus) {
                val back = if (dir == FocusDirection.Next) {
                    runCatching { area.entry.requestFocus() }.getOrDefault(false) && area.hasFocus
                } else {
                    // The last item: enter at the first, walk forward until the next step would
                    // leave, then step back (bounded, a long list doesn't hang the car screen).
                    var ok = runCatching { area.entry.requestFocus() }.getOrDefault(false) && area.hasFocus
                    if (ok) {
                        for (i in 0 until MAX_WRAP_STEPS) {
                            focus.moveFocus(FocusDirection.Next)
                            if (!area.hasFocus) { focus.moveFocus(FocusDirection.Previous); break }
                        }
                        ok = area.hasFocus
                    }
                    ok
                }
                Log.d("AareFocus", "knob ${if (dir == FocusDirection.Next) "next" else "previous"}: left the pane (moved=$moved), wrapped=$back")
            }
        }
        true
    }
}

/** How many items a backward wrap walks at most: past that, a long list stops short of its end. */
private const val MAX_WRAP_STEPS = 200
