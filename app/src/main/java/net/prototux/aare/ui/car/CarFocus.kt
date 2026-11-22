package net.prototux.aare.ui.car

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
 * Whether the car's last input was a navigation key rather than a touch (main thread). Car touches
 * are injected straight into the window, so they never put it back in touch mode once a knob took
 * it out: on a unit with both, the highlight would otherwise stay up and follow the taps.
 */
object CarFocusVisibility {
    var keyInput by mutableStateOf(false)
}
