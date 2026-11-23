package net.prototux.aare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.prototux.aare.R

/** The row being dragged to a new place in a list, by its key, and how far it is from its slot. */
internal class RowDrag {
    var key by mutableStateOf<Any?>(null)
    var offset by mutableFloatStateOf(0f)
}

/** The step a dragged row [offset] pixels from its slot takes in its list: -1, 0 or 1, once past half a row of [height]. */
internal fun dragStep(offset: Float, height: Float, index: Int, count: Int): Int = when {
    offset > height / 2 && index < count - 1 -> 1
    offset < -height / 2 && index > 0 -> -1
    else -> 0
}

/**
 * A row sorted by dragging its handle (or by accessibility actions), [index] of [count]: [move] takes
 * it a step up or down. Inside `key([itemKey])`, so the gesture goes on as the row changes places.
 * With [sortable] off, no handle.
 */
@Composable
internal fun SortableRow(
    itemKey: Any, index: Int, count: Int, drag: RowDrag, modifier: Modifier = Modifier, sortable: Boolean = true,
    move: (by: Int) -> Unit, content: @Composable RowScope.() -> Unit,
) {
    var height by remember { mutableIntStateOf(1) }
    val i by rememberUpdatedState(index)
    val n by rememberUpdatedState(count)
    val step by rememberUpdatedState(move)
    val up = stringResource(R.string.common_up)
    val down = stringResource(R.string.common_down)
    val dragged = if (drag.key != itemKey) Modifier else Modifier.zIndex(1f)
        .graphicsLayer { translationY = drag.offset; shadowElevation = 8.dp.toPx() }.background(MaterialTheme.colorScheme.surface)
    val actions = if (!sortable) Modifier else Modifier.semantics {
        customActions = listOfNotNull(
            CustomAccessibilityAction(up) { move(-1); true }.takeIf { index > 0 },
            CustomAccessibilityAction(down) { move(1); true }.takeIf { index < count - 1 },
        )
    }
    Row(dragged.fillMaxWidth().onSizeChanged { height = it.height }.then(actions).then(modifier), verticalAlignment = Alignment.CenterVertically) {
        content()
        val gesture = if (!sortable) Modifier else Modifier.pointerInput(Unit) {
            // One step at a time: the next waits until the list has the row in its new place.
            var waitingAt = -1
            fun drop() { drag.key = null; drag.offset = 0f }
            detectVerticalDragGestures(
                onDragStart = { drag.key = itemKey; drag.offset = 0f; waitingAt = -1 },
                onDragEnd = { drop() },
                onDragCancel = { drop() },
            ) { change, dy ->
                change.consume()
                drag.offset += dy
                if (i == waitingAt) return@detectVerticalDragGestures
                waitingAt = -1
                val by = dragStep(drag.offset, height.toFloat(), i, n)
                if (by != 0) { waitingAt = i; drag.offset -= by * height; step(by) }
            }
        }
        Icon(Icons.Rounded.DragIndicator, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = gesture.padding(8.dp).size(32.dp).alpha(if (sortable) 1f else 0f))
    }
}
