package net.prototux.aare.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A floating island: [center] (a circle, [centerSize] across) with [start] and [end] on a pill
 * [pillHeight] tall behind them. [unfold] (0..1): how far the sides are out of the circle; folded,
 * they slide into it and only the circle is left. [rise] (0..1): 1 at rest, 0 off the screen's
 * bottom edge ([fromTop]: the top) and transparent. Both are read in layout and draw only, so
 * animating them doesn't recompose.
 */
@Composable
internal fun Island(
    unfold: () -> Float, rise: () -> Float, fromTop: Boolean,
    pillHeight: Dp, centerSize: Dp, pillColor: Color, modifier: Modifier = Modifier,
    start: @Composable RowScope.() -> Unit = {}, center: @Composable () -> Unit, end: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier.graphicsLayer {
            val r = rise()
            alpha = r.coerceIn(0f, 1f)
            // Unclamped, so the spring's overshoot carries it a little past rest and back.
            translationY = (1f - r) * OFF_SCREEN.toPx() * (if (fromTop) -1f else 1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        // The pill: as tall as the side buttons, as wide as the island is now.
        Box(Modifier.matchParentSize().padding(vertical = ((centerSize - pillHeight) / 2).coerceAtLeast(0.dp)).clip(RoundedCornerShape(percent = 50)).background(pillColor))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.fold(unfold, toEnd = true).padding(start = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = start)
            center()
            Row(Modifier.fold(unfold, toEnd = false).padding(start = 8.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = end)
        }
    }
}

/** Only [fraction] of the width (a little more while it overshoots), the content sliding toward the middle ([toEnd]: the left side) and cut off there. */
private fun Modifier.fold(fraction: () -> Float, toEnd: Boolean): Modifier = clipToBounds().layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0))
    val w = (p.width * fraction().coerceIn(0f, MAX_UNFOLD)).roundToInt()
    layout(w, p.height) { p.place(if (toEnd) 0 else w - p.width, 0) }
}

/** Far enough to be past the screen's edge from where an island sits. */
private val OFF_SCREEN = 200.dp

/** Rise, then unfold once it's nearly up; both on a spring that overshoots a few percent and settles. */
internal suspend fun islandShow(rise: Animatable<Float, AnimationVector1D>, unfold: Animatable<Float, AnimationVector1D>) = coroutineScope {
    launch { rise.animateTo(1f, ENTER) }
    snapshotFlow { rise.value }.first { it >= UNFOLD_FROM }
    unfold.animateTo(1f, ENTER)
}

/** Fold the sides in, then sink away; eased in and out, no bounce. */
internal suspend fun islandHide(rise: Animatable<Float, AnimationVector1D>, unfold: Animatable<Float, AnimationVector1D>) {
    unfold.animateTo(0f, tween(380, easing = EASE_IN_OUT))
    rise.animateTo(0f, tween(440, easing = EASE_IN_OUT))
}

/** Damping 0.65 overshoots by about 7%; stiffness 260 settles in roughly 0.4 s. */
private val ENTER = spring<Float>(dampingRatio = 0.65f, stiffness = 260f)
private val EASE_IN_OUT = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private const val UNFOLD_FROM = 0.9f
private const val MAX_UNFOLD = 1.2f
