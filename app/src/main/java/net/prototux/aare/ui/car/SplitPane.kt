package net.prototux.aare.ui.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Two apps side by side. Dragging the handle between them moves a line over the apps; let go,
 * the panes take that size, or within [SPLIT_SNAP] of an edge the pane on that side closes and the
 * other app takes the whole screen (that pane darkens while the line is there). The apps are only
 * resized once, when the handle is let go: resizing them on every move cost a new surface each
 * frame, and a navigation app could miss the last one and leave its map short.
 */
@Composable
internal fun SplitPane(
    split: CarScreen.Split,
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
) {
    // The left pane's share under the finger while the handle is dragged, else null.
    var drag by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val f = state.splitFraction.coerceIn(SPLIT_SNAP, 1 - SPLIT_SNAP)
        Row(Modifier.fillMaxSize()) {
            Pane(split.left, true, state, appContent, Modifier.weight(f))
            Gap()
            Pane(split.right, false, state, appContent, Modifier.weight(1 - f))
        }
        drag?.let { DragPreview(it, f) }
        // The handle's touch area reaches over the panes' edges: easy to grab in a moving car
        // while the gap between the panes stays narrow.
        val density = LocalDensity.current
        val gap = with(density) { GAP.toPx() }
        val grab = with(density) { GRAB.toPx() }
        val center = f * (width - gap) + gap / 2
        val label = stringResource(R.string.split_divider)
        Box(
            Modifier.offset { IntOffset((center - grab / 2).roundToInt(), 0) }.width(GRAB).fillMaxHeight()
                .semantics { contentDescription = label }
                .pointerInput(width, f) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = f },
                        onDragEnd = { drag?.let(actions::releaseDivider); drag = null },
                        onDragCancel = { drag = null },
                    ) { change, dx ->
                        change.consume()
                        drag = ((drag ?: f) + dx / width).coerceIn(0f, 1f)
                    }
                },
        )
    }
}

private val GAP = 12.dp
private val GRAB = 48.dp

@Composable
private fun Pane(
    appId: String,
    left: Boolean,
    state: CarUiState,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier.fillMaxHeight().clipToBounds()
            // A rotary knob or D-pad moving into the pane counts as touching it.
            .onFocusChanged { if (it.hasFocus) state.splitActiveLeft = left }
            // Watches touches without taking them: the car's Back key goes to the pane touched last.
            .pointerInput(left) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        state.splitActiveLeft = left
                    }
                }
            },
    ) {
        state.app(appId)?.let { appContent(it, Modifier.fillMaxSize()) }
    }
}

/** The narrow gap between the panes, with the handle drawn as a short bar. */
@Composable
private fun Gap() {
    Box(Modifier.width(GAP).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 4.dp, height = 56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)))
    }
}

/**
 * Drawn over the apps while the handle is dragged: the line where it would go, the strip that
 * changes sides lightly shaded, and a pane about to close darkened. A scrim rather than alpha:
 * alpha doesn't dim a navigation app's map surface.
 */
@Composable
private fun DragPreview(fraction: Float, committed: Float) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val x = fraction * size.width
        val from = committed * size.width
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(min(x, from), 0f), Size(abs(x - from), size.height))
        if (fraction < SPLIT_SNAP) drawRect(Color.Black.copy(alpha = 0.6f), Offset.Zero, Size(x, size.height))
        if (fraction > 1 - SPLIT_SNAP) drawRect(Color.Black.copy(alpha = 0.6f), Offset(x, 0f), Size(size.width - x, size.height))
        // A line the full height, easy to see over any app, with the handle on it.
        drawRect(line, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        val w = 8.dp.toPx()
        val h = 64.dp.toPx()
        drawRoundRect(line, Offset(x - w / 2, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
    }
}

/**
 * Asked for by a long press on a rail app: which pane to open [app] in. [anchor] is the center of
 * the pressed icon in the shell's coordinates; [horizontal] tells a bottom bar from a side rail.
 */
internal class PaneChoice(val app: CarAppEntry, val anchor: Offset, val horizontal: Boolean, val railOnRight: Boolean)

/**
 * The two pane icons next to the pressed rail app, over everything else. Drawn in the car screen's
 * own window, not a popup: the head unit's touches only reach that window. A tap elsewhere closes it.
 */
@Composable
internal fun PaneChooser(choice: PaneChoice, onPick: (left: Boolean) -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val gap = with(density) { (LocalCarDimens.current.touchTarget / 2 + 12.dp).toPx() }
    // Two 64 dp options with 4 dp padding around and between them.
    val w = with(density) { 140.dp.toPx() }
    val h = with(density) { 72.dp.toPx() }
    val (x, y) = when {
        choice.horizontal -> choice.anchor.x - w / 2 to choice.anchor.y - gap - h
        choice.railOnRight -> choice.anchor.x - gap - w to choice.anchor.y - h / 2
        else -> choice.anchor.x + gap to choice.anchor.y - h / 2
    }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        val maxX = constraints.maxWidth - w
        val maxY = constraints.maxHeight - h
        Row(
            Modifier.offset { IntOffset(x.coerceIn(0f, maxOf(0f, maxX)).roundToInt(), y.coerceIn(0f, maxOf(0f, maxY)).roundToInt()) }
                // Lifted off whatever is behind it, often an app's own panel in the same dark colors.
                .shadow(12.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PaneOption(left = true, stringResource(R.string.split_open_left)) { onPick(true) }
            PaneOption(left = false, stringResource(R.string.split_open_right)) { onPick(false) }
        }
    }
}

@Composable
private fun PaneOption(left: Boolean, label: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PaneIcon(left, color)
    }
}

/** A screen split in two, the chosen half filled. */
@Composable
private fun PaneIcon(left: Boolean, color: Color) {
    Canvas(Modifier.size(width = 40.dp, height = 28.dp)) {
        val stroke = 2.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        val half = size.width / 2
        drawRoundRect(color, topLeft = Offset(if (left) 0f else half, 0f), size = Size(half, size.height), cornerRadius = radius)
        drawRoundRect(color, cornerRadius = radius, style = Stroke(stroke))
        drawLine(color, Offset(half, 0f), Offset(half, size.height), stroke)
    }
}
