package net.prototux.aare.ui.car

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * A clear focus highlight for non-touch head units driven by a rotary controller or D-pad: a thick
 * border on the element's shape while it holds focus, so the driver can see where they are, lined
 * with a contrasting ring and a dim veil so it also shows on elements filled with the border's color.
 *
 * Place it directly before the element's own `.clickable`, whose focus target it observes (a
 * clickable is focusable by default, so Compose's D-pad traversal already reaches it; this only
 * makes the focus visible). It draws nothing until something is focused, so touch head units,
 * which never move focus, are unaffected; and nothing after a touch, see [CarFocusVisibility].
 */
fun Modifier.carFocusHighlight(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val inner = MaterialTheme.colorScheme.onPrimary
    val shown = focused && CarFocusVisibility.keyInput
    onFocusChanged { focused = it.isFocused }
        .border(width = if (shown) 3.dp else 0.dp, color = if (shown) color else Color.Transparent, shape = shape)
        // Inside it, a dim veil and a second ring in the color that reads on the first: on a button
        // filled with the highlight's own color (the play button), the border alone can't be seen.
        .drawWithContent {
            // The veil goes under the content (over the element's own background), so labels stay sharp.
            if (shown) drawOutline(shape.createOutline(size, layoutDirection, this), inner.copy(alpha = 0.3f))
            drawContent()
            if (!shown) return@drawWithContent
            val w = 4.dp.toPx()
            val at = 3.dp.toPx() + w / 2 // the middle of the ring, past the 3 dp border
            if (size.minDimension > at * 4) {
                val outline = shape.createOutline(Size(size.width - at * 2, size.height - at * 2), layoutDirection, this)
                translate(at, at) { drawOutline(outline, inner, style = Stroke(w)) }
            }
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
     * Whether the car has a rotary knob (it sent a turn). Only then does tilting jump between
     * panes, and do touch-only screens get a cursor, see [CursorPane]: a D-pad alone keeps moving
     * from item to item.
     */
    var rotary by mutableStateOf(false)
}

/**
 * One pane for the rotary knob, like Android Auto's focus areas: turning the knob (Tab and
 * Shift+Tab, see CarSession) moves focus only among this pane's items, looping from the last back
 * to the first, see [rotaryWrap]. Tilting the knob (the D-pad) still moves between panes, except
 * in a pane laid out as a grid ([tiltWithin], a keyboard), where it moves from item to item and
 * leaves at the edge.
 *
 * A [floating] pane lies over the others (the call strip): it isn't beside anything, so a tilt
 * reaches it, and leaves it, by the panes' centres instead of their edges.
 */
fun Modifier.rotaryArea(tiltWithin: Boolean = false, floating: Boolean = false): Modifier = composed {
    // Entering through a FocusRequester lands on the pane's first item (as the screen's starting
    // focus does); the node's own requestFocus doesn't, a group isn't focusable itself.
    val entry = remember { FocusRequester() }
    focusRequester(entry) then RotaryAreaElement(entry, tiltWithin, floating)
}

private data class RotaryAreaElement(val entry: FocusRequester, val tiltWithin: Boolean, val floating: Boolean) : ModifierNodeElement<RotaryAreaNode>() {
    override fun create() = RotaryAreaNode(entry, tiltWithin, floating)
    override fun update(node: RotaryAreaNode) { node.entry = entry; node.tiltWithin = tiltWithin; node.floating = floating }
}

/** A focus group whose focus state reads synchronously, unlike onFocusChanged's (dispatched later). */
internal class RotaryAreaNode(var entry: FocusRequester, var tiltWithin: Boolean = false, var floating: Boolean = false) : DelegatingNode(), GlobalPositionAwareModifierNode, TraversableNode {
    val target = delegate(FocusTargetModifierNode(Focusability.Never))
    val hasFocus: Boolean get() = isAttached && target.focusState.hasFocus
    var bounds: Rect = Rect.Zero
    override val traverseKey: Any get() = RotaryAreaKey
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        bounds = if (coordinates.isAttached) coordinates.boundsInRoot() else Rect.Zero
    }
    override fun onAttach() { RotaryAreas.areas += this }
    override fun onDetach() { RotaryAreas.areas -= this }
}

/** The car screen's [rotaryArea]s (main thread; one car screen at a time). */
internal object RotaryAreas {
    val areas = mutableListOf<RotaryAreaNode>()

    /** The panes holding others (the screen's content around the dashboard cards). */
    private fun parents(): Set<RotaryAreaNode> {
        val out = HashSet<RotaryAreaNode>()
        for (a in areas) if (a.isAttached) a.traverseAncestors(RotaryAreaKey) { out += it as RotaryAreaNode; true }
        return out
    }

    /** The innermost pane holding focus. */
    fun focused(): RotaryAreaNode? {
        val focused = areas.filter { it.hasFocus }
        val outer = parents()
        return focused.firstOrNull { it !in outer } ?: focused.lastOrNull()
    }

    /**
     * The innermost panes beside [from] towards [dir], nearest first (closest edge, then best lined
     * up). Floating panes, which overlap the others, are compared by their centres and come after
     * the panes beside [from], so a tilt only lands on one where nothing else is that way.
     */
    fun beside(from: RotaryAreaNode, dir: FocusDirection): List<RotaryAreaNode> {
        val f = from.bounds
        if (f.isEmpty) return emptyList()
        val outer = parents()
        val candidates = areas.filter { it !== from && it.isAttached && it !in outer && !it.bounds.isEmpty }
        fun score(a: RotaryAreaNode, byCentre: Boolean): Float? {
            val b = a.bounds
            val (gap, off) = when (dir) {
                FocusDirection.Left -> (if (byCentre) f.center.x - b.center.x else f.left - b.right) to (b.center.y - f.center.y)
                FocusDirection.Right -> (if (byCentre) b.center.x - f.center.x else b.left - f.right) to (b.center.y - f.center.y)
                FocusDirection.Up -> (if (byCentre) f.center.y - b.center.y else f.top - b.bottom) to (b.center.x - f.center.x)
                else -> (if (byCentre) b.center.y - f.center.y else b.top - f.bottom) to (b.center.x - f.center.x)
            }
            // Beyond from's edge (a little overlap allowed), not merely overlapping it; by centre, that way at all.
            if (if (byCentre) gap <= 0f else gap < -EDGE_SLACK) return null
            return 13 * gap.coerceAtLeast(0f).let { it * it } + off * off
        }
        val (overlapping, beside) = candidates.partition { from.floating || it.floating }
        fun ranked(list: List<RotaryAreaNode>, byCentre: Boolean) =
            list.mapNotNull { a -> score(a, byCentre)?.let { a to it } }.sortedBy { it.second }.map { it.first }
        return ranked(beside, false) + ranked(overlapping, true)
    }
}

/**
 * Takes the knob's keys before panes and cursors (main thread): a hosted map in pan mode, where
 * tilts move the map and turns zoom, like Android Auto. The handler returns null for a key it
 * leaves alone.
 */
internal object CarKnobCapture {
    var handler: ((KeyEvent) -> Boolean?)? = null
}

private object RotaryAreaKey

/** How far (px) panes may overlap and still count as side by side. */
private const val EDGE_SLACK = 24f

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
        // On a touch-only screen the knob drives a cursor, until a tilt takes it past the edge.
        // Something that takes the knob for itself (a hosted map in pan mode) goes first.
        CarKnobCapture.handler?.invoke(e)?.let { return@onPreviewKeyEvent it }
        CarCursors.active()?.onKey(e)?.let { return@onPreviewKeyEvent it }
        val tilt = when (e.key) {
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> null
        }
        if (tilt != null) return@onPreviewKeyEvent tiltToPane(e.type, tilt)
        if (e.key != Key.Tab) return@onPreviewKeyEvent false
        val area = RotaryAreas.focused() ?: return@onPreviewKeyEvent false
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

/**
 * A knob tilt (D-pad) on a car with a knob: focus jumps to the next pane that way, whatever item
 * it was on, like Android Auto; the turn then moves within it. With no pane that way, the tilt
 * moves focus as usual (a list or a hosted app inside the pane). Cars without a knob, whose D-pad
 * is the only way to move between items, keep the usual D-pad behavior.
 */
private fun tiltToPane(type: KeyEventType, dir: FocusDirection): Boolean {
    if (!CarFocusVisibility.rotary) return false
    val from = RotaryAreas.focused() ?: return false
    // A keyboard: the tilt moves to the next key, and past its edge to whatever is that way.
    if (from.tiltWithin) return false
    val panes = RotaryAreas.beside(from, dir)
    if (panes.isEmpty()) return false
    if (type != KeyEventType.KeyDown) return true
    // The nearest pane with something to focus (a card with no buttons can't take it).
    val to = panes.firstOrNull { runCatching { it.entry.requestFocus() }.getOrDefault(false) && it.hasFocus }
    Log.d("AareFocus", "knob tilt $dir: ${if (to != null) "moved to the next pane" else "no focusable pane that way"}")
    // Nothing to land on: let the tilt move focus as usual.
    return to != null
}
