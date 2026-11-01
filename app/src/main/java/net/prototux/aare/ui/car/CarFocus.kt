package net.prototux.aare.ui.car

import androidx.compose.foundation.border
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
 * which never move focus, are unaffected.
 */
fun Modifier.carFocusHighlight(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.isFocused }
        .border(width = if (focused) 3.dp else 0.dp, color = if (focused) color else Color.Transparent, shape = shape)
}
