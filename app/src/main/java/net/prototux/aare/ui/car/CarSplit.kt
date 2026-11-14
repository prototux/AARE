package net.prototux.aare.ui.car

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Which side of the divider a pane is on, while two apps share the car screen. */
enum class CarPane { PRIMARY, SECONDARY }

/**
 * Below this, two panes can't both stay usable, so split screen doesn't engage even when the
 * setting is on (the toggle still shows: a head unit can be plugged into a wider screen later).
 */
val SPLIT_MIN_SCREEN_WIDTH = 900.dp

/** Neither pane is ever dragged thinner than this while live-dragging the divider. */
private val SPLIT_MIN_PANE_WIDTH = 240.dp

/** The divider's touch strip; the visible line drawn inside it is thinner. */
val SPLIT_DIVIDER_WIDTH = 32.dp

/**
 * Where the divider rests once released: a third, half or two thirds of the content width. The
 * first and last points aren't real rest ratios — [snapSplitRatio] landing on one of them means
 * the pane on that side closes instead, and the other becomes the single full-screen app again.
 */
private val SPLIT_SNAP_RATIOS = floatArrayOf(0f, 1f / 3f, 0.5f, 2f / 3f, 1f)

/**
 * Car apps that can take the second pane: built-ins and non-hosted screens the dashboard already
 * runs beside the map today (media, phone, notifications…), none of which binds a second Car App
 * Library connection. A navigation/templated/assistant app keeps CarAppConnection's one-surface-
 * owner-at-a-time assumption (see CarAppConnection.surfaceAvailable): it only runs full screen or
 * on the dashboard's map card, never in both panes of a split. The phone mirror and a launched
 * phone app are single, device-wide resources (one phone screen, one virtual display per package
 * already used elsewhere) that aren't worth doubling up for a first version of split screen.
 */
private val SPLIT_SECONDARY_KINDS = setOf(
    CarAppKind.MEDIA, CarAppKind.PHONE, CarAppKind.MESSAGING, CarAppKind.BROWSER,
    CarAppKind.CAR_DATA, CarAppKind.SETTINGS, CarAppKind.NOTIFICATIONS,
)

/** Whether [kind] may be opened in the second pane, beside whatever is already the main app. */
fun canOpenBeside(kind: CarAppKind): Boolean = kind in SPLIT_SECONDARY_KINDS

/** Whether the content area is wide enough to split at all. */
fun splitScreenFits(width: Dp): Boolean = width >= SPLIT_MIN_SCREEN_WIDTH

/**
 * Snaps a divider drag (0..1, the primary pane's share of the width) to the nearest rest point.
 * 0f or 1f means the pane on that side closes rather than a real ratio: the caller checks for
 * those two values before storing the result as [CarUiState.splitRatio].
 */
fun snapSplitRatio(raw: Float): Float {
    val clamped = raw.coerceIn(0f, 1f)
    return SPLIT_SNAP_RATIOS.minByOrNull { abs(it - clamped) } ?: 0.5f
}

/** The narrower of the two panes' width at [ratio] of [totalWidth], the divider's own width set aside. */
fun minPaneWidth(totalWidth: Dp, ratio: Float): Dp {
    val usable = (totalWidth.value - SPLIT_DIVIDER_WIDTH.value).coerceAtLeast(0f)
    return (usable * minOf(ratio, 1f - ratio).coerceIn(0f, 1f)).dp
}

/**
 * Clamps a live divider drag so neither pane is ever drawn thinner than [SPLIT_MIN_PANE_WIDTH],
 * before it's released and [snapSplitRatio] decides the real rest point (or a close).
 */
fun clampDragRatio(totalWidth: Dp, raw: Float): Float {
    val usable = (totalWidth.value - SPLIT_DIVIDER_WIDTH.value).coerceAtLeast(1f)
    val minFraction = (SPLIT_MIN_PANE_WIDTH.value / usable).coerceIn(0f, 0.5f)
    return raw.coerceIn(minFraction, 1f - minFraction)
}

/**
 * Which pane a point at [x] along [totalWidth] falls in, the divider resting at [ratio]. Compose's
 * own layout already routes touch this way (each pane is its own box); this is the same geometry,
 * kept as a pure function so it has a unit test independent of a composition.
 */
fun paneAt(x: Dp, totalWidth: Dp, ratio: Float): CarPane {
    val dividerStart = (totalWidth.value - SPLIT_DIVIDER_WIDTH.value) * ratio.coerceIn(0f, 1f)
    return if (x.value < dividerStart + SPLIT_DIVIDER_WIDTH.value / 2f) CarPane.PRIMARY else CarPane.SECONDARY
}
