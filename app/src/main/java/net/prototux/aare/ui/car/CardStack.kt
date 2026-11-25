package net.prototux.aare.ui.car

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Cards sharing one place: a swipe goes from one to the next, round and round, and with [seconds]
 * above 0 those of [turning] also change by themselves, except while one is touched or holds the
 * knob's focus. The dots are the knob's way: select them, then turn.
 *
 * Which card is shown ([current]) is decided as the finger lifts, and changes there and then; the
 * slide only follows it home. So a slide cut short (by the next swipe, by a turn) never leaves two
 * halves on show, nor the dots on another card, and swipes in a row all count.
 *
 * Not a HorizontalPager: going round needs an endless one, and the knob's focus, which a pager
 * follows from page to page, would never get out of it. Here only the card shown exists, plus its
 * neighbour while they slide.
 */
@Composable
internal fun CardStack(
    cards: List<DashItem>,
    turning: List<DashItem>,
    /** Read at once on every swipe, not as of the last drawing: two can come in one frame. */
    current: () -> DashItem,
    onCurrent: (DashItem) -> Unit,
    seconds: Int,
    modifier: Modifier,
    card: @Composable (DashItem, Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val all by rememberUpdatedState(cards)
    val now by rememberUpdatedState(current)
    val select by rememberUpdatedState(onCurrent)
    val enough = with(LocalDensity.current) { SWIPE.toPx() }
    var width by remember { mutableIntStateOf(1) }
    /** How far the card shown is from its place, in pixels; its neighbours are a card's width to each side. */
    var offset by remember { mutableFloatStateOf(0f) }
    /** The card sliding out, when it isn't the neighbour on that side (a turn past the cards that take none). */
    var leaving by remember { mutableStateOf<DashItem?>(null) }
    val slide = remember { arrayOfNulls<Job>(1) }
    var touched by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    /** The furthest it got, on the side it is on. */
    var furthest by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }

    /** Shows [target], [step] cards on (0: the one already shown), from where the finger left it. */
    fun settle(step: Int, target: DashItem) {
        slide[0]?.cancel()
        if (step != 0) {
            leaving = now()
            select(target)
            offset += step * width
        }
        slide[0] = scope.launch {
            animate(offset, 0f, animationSpec = tween(SLIDE_MS)) { value, _ -> offset = value }
            leaving = null
        }
    }

    fun go(step: Int) { if (all.size > 1) settle(step, stackNeighbour(all, now(), step)) }

    val shown = current()
    val paused = touched || (focused && CarFocusVisibility.keyInput)
    LaunchedEffect(seconds, shown, turning, paused) {
        if (seconds <= 0 || paused) return@LaunchedEffect
        val next = nextInTurn(turning, shown) ?: return@LaunchedEffect
        delay(seconds * 1000L)
        settle(1, next)
    }

    val shape = RoundedCornerShape(LocalCarDimens.current.cardRadius)
    val previousLabel = stringResource(R.string.dash_stack_previous)
    val nextLabel = stringResource(R.string.dash_stack_next)
    Box(
        modifier
            .clip(shape)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .onFocusChanged { focused = it.hasFocus }
            .semantics { customActions = listOf(CustomAccessibilityAction(previousLabel) { go(-1); true }, CustomAccessibilityAction(nextLabel) { go(1); true }) }
            .draggable(
                rememberDraggableState { d ->
                    dragged += d
                    if (dragged * furthest < 0 || abs(dragged) > abs(furthest)) furthest = dragged
                    // No further than the neighbour; what a slide left beyond it only comes back.
                    offset = (offset + d).coerceIn(minOf(-width.toFloat(), offset), maxOf(width.toFloat(), offset))
                },
                Orientation.Horizontal,
                onDragStarted = { slide[0]?.cancel(); leaving = null; dragged = 0f; furthest = 0f; touched = true },
                onDragStopped = { touched = false; go(swipeStep(dragged, furthest, enough)) },
            ),
    ) {
        val side by remember { derivedStateOf { if (offset < 0) 1 else if (offset > 0) -1 else 0 } }
        val other = if (side == 0) null else leaving ?: stackNeighbour(cards, shown, side)
        // Keyed by card: the one sliding in is not drawn anew when it becomes the one shown.
        listOfNotNull(shown, other).distinct().forEach { item ->
            key(item) { card(item, Modifier.fillMaxSize().graphicsLayer { translationX = offset + if (item == shown) 0 else side * width }) }
        }
        if (cards.size > 1) Dots(
            cards.size, cards.indexOf(shown).coerceAtLeast(0),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).carAdjustable(CircleShape) { go(it) },
        )
    }
}

private const val SLIDE_MS = 260

/** A drag this long, past the slop that starts it, changes the card. */
private val SWIPE = 24.dp

/** The card [step] places after [current] in [cards], round and round. */
internal fun stackNeighbour(cards: List<DashItem>, current: DashItem, step: Int): DashItem =
    if (cards.isEmpty()) current else cards[(cards.indexOf(current).coerceAtLeast(0) + step).mod(cards.size)]

/**
 * Where a finger that [dragged] so many pixels (left is negative) and got as far as [furthest] goes
 * as it lifts: the next card (1), the previous one (-1), or back (0: not [enough], or drawn back).
 * By distance alone: with the phone busy, or a head unit that sends its touches in bursts, the end
 * of a swipe comes late and all at once, and its speed reads as anything, often none.
 */
internal fun swipeStep(dragged: Float, furthest: Float, enough: Float): Int = when {
    abs(dragged) < enough || abs(dragged) < abs(furthest) * 0.6f -> 0
    else -> if (dragged < 0) 1 else -1
}

/** Which of [count] cards is shown: the one at [index], as a wide mark among dots (a number, past what dots can tell). */
@Composable
private fun Dots(count: Int, index: Int, modifier: Modifier) {
    val label = stringResource(R.string.dash_stack_position, index + 1, count)
    Row(
        // On a patch of the card's colour: a custom card can have any background.
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)).clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > MAX_DOTS) Text("${index + 1} / $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        else repeat(count) { i ->
            Box(Modifier.size(width = if (i == index) 18.dp else 7.dp, height = 7.dp).clip(CircleShape)
                .background(if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)))
        }
    }
}

private const val MAX_DOTS = 10
