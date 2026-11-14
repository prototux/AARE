package net.prototux.aare.ui.car

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A pointer for the screens that only take touches (the phone's mirror, a hosted phone app, a web
 * page), on cars driven by a rotary knob: there is nothing in them for the focus to land on, so
 * the knob drives a cursor instead. Tilting moves it (held, it keeps going, faster), pressing taps
 * (or holds) under it, turning scrolls. Tilting against an edge leaves for the pane beyond it, as
 * between any two panes, see [rotaryWrap].
 *
 * The cursor's touches go into the car screen like the head unit's own, so [content] needs nothing
 * more than the touch handling it already has. Cars without a knob (touch screens, D-pads) never
 * see any of it: until the car sends a knob turn this is a plain box.
 */
@Composable
internal fun CursorPane(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val cursor = remember(view) { CarCursor(view, scope, density) }
    DisposableEffect(cursor) {
        CarCursors.panes += cursor
        onDispose { CarCursors.panes -= cursor; cursor.cancel() }
    }
    val knob = CarFocusVisibility.rotary
    val shown = knob && cursor.focused && CarFocusVisibility.keyInput
    Box(
        modifier
            .rotaryArea()
            .onGloballyPositioned { cursor.place(it.boundsInRoot()) }
            .onFocusChanged { cursor.focus(it.hasFocus) }
            // The pane itself takes the focus: its content has nothing focusable of its own.
            .then(if (knob) Modifier.focusable() else Modifier)
            .drawWithContent {
                drawContent()
                if (shown) drawPointer(cursor.position, cursor.pressed)
            },
        content = content,
    )
}

/** The car screen's cursor panes (main thread). */
internal object CarCursors {
    val panes = mutableListOf<CarCursor>()

    /** The pane whose cursor the knob drives now, if any. */
    fun active(): CarCursor? = panes.lastOrNull { it.active }
}

/** One [CursorPane]'s cursor: where it is, and the touches it makes (main thread). */
internal class CarCursor(private val view: View, private val scope: CoroutineScope, private val density: Float) {
    /** Where the cursor points, in the pane. */
    var position by mutableStateOf(Offset.Unspecified)
        private set
    var pressed by mutableStateOf(false)
        private set
    var focused by mutableStateOf(false)
        private set

    private var bounds = Rect.Zero
    private var held: Key? = null
    private var moveJob: Job? = null
    private var releaseJob: Job? = null
    private var scrollJob: Job? = null
    private var pendingScroll = 0
    private var downTime = 0L
    /** A touch of ours is down on the screen (a press or a scroll). */
    private var touching = false

    val active: Boolean get() = focused && CarFocusVisibility.rotary && !bounds.isEmpty && position.isSpecified

    fun place(b: Rect) {
        bounds = b
        if (b.isEmpty) return
        position = if (position.isSpecified) clamp(position) else Offset(b.width / 2, b.height / 2)
    }

    fun focus(has: Boolean) {
        focused = has
        if (!has) cancel()
    }

    /** Lets go of everything: the focus left, or the pane is gone. */
    fun cancel() {
        stopMove()
        releaseJob?.cancel(); releaseJob = null
        scrollJob?.cancel(); scrollJob = null
        pendingScroll = 0
        if (touching) inject(MotionEvent.ACTION_CANCEL, position)
        pressed = false
    }

    /** A key from the knob: true or false once dealt with, null when it isn't the cursor's (the tilt that leaves the pane). */
    fun onKey(e: KeyEvent): Boolean? {
        val down = when (e.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> false
            else -> return null
        }
        return when (e.key) {
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> tilt(e.key, down)
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (down) press() else release(); true }
            Key.Tab -> { if (down) scroll(if (e.isShiftPressed) -1 else 1); true }
            else -> null
        }
    }

    private fun tilt(key: Key, down: Boolean): Boolean? {
        if (!down) {
            if (held != key) return null
            stopMove()
            return true
        }
        if (held == key) return true // the car repeating a held key
        val dir = when (key) {
            Key.DirectionLeft -> Offset(-1f, 0f)
            Key.DirectionRight -> Offset(1f, 0f)
            Key.DirectionUp -> Offset(0f, -1f)
            else -> Offset(0f, 1f)
        }
        // Already against that edge: the tilt leaves the pane.
        if (clamp(position + dir) == position) return null
        stopMove()
        held = key
        // A nudge right away (a car may send the release with the press), then for as long as it's held.
        move(dir * (NUDGE_DP * density))
        moveJob = scope.launch {
            val start = SystemClock.uptimeMillis()
            var last = start
            // Bounded: a lost release must not leave the cursor running.
            while (last - start < MAX_HOLD_MS) {
                delay(FRAME_MS)
                val now = SystemClock.uptimeMillis()
                val ramp = ((now - start) / RAMP_MS).coerceAtMost(1f)
                val speed = (MIN_SPEED_DP + (MAX_SPEED_DP - MIN_SPEED_DP) * ramp) * density
                move(dir * (speed * (now - last) / 1000f))
                last = now
            }
            if (held == key) held = null
        }
        return true
    }

    private fun stopMove() {
        moveJob?.cancel(); moveJob = null
        held = null
    }

    private fun move(by: Offset) {
        val to = clamp(position + by)
        if (to == position) return
        position = to
        if (pressed) inject(MotionEvent.ACTION_MOVE, to) // dragging
    }

    private fun clamp(p: Offset) = Offset(
        p.x.coerceIn(0f, (bounds.width - 1f).coerceAtLeast(0f)),
        p.y.coerceIn(0f, (bounds.height - 1f).coerceAtLeast(0f)),
    )

    private fun press() {
        if (pressed || touching) return
        downTime = SystemClock.uptimeMillis()
        pressed = true
        inject(MotionEvent.ACTION_DOWN, position)
    }

    private fun release() {
        // Only what was pressed here: the release of the press that opened this screen is not a tap.
        if (!pressed || releaseJob?.isActive == true) return
        // Not sooner than a real tap lasts: apps drop a press and release in the same instant.
        val wait = MIN_TAP_MS - (SystemClock.uptimeMillis() - downTime)
        releaseJob = scope.launch {
            if (wait > 0) delay(wait)
            inject(MotionEvent.ACTION_UP, position)
            pressed = false
        }
    }

    /** Knob detents add up while a swipe is under way, so a quick turn scrolls further, not later. */
    private fun scroll(detents: Int) {
        if (pressed) return
        pendingScroll = (pendingScroll + detents).coerceIn(-MAX_DETENTS, MAX_DETENTS)
        if (scrollJob?.isActive == true) return
        scrollJob = scope.launch {
            while (pendingScroll != 0) {
                val n = pendingScroll
                pendingScroll = 0
                swipe(n)
                delay(SWIPE_GAP_MS)
            }
        }
    }

    /** A swipe under the cursor: turning on scrolls on, so the finger goes up. */
    private suspend fun swipe(detents: Int) {
        val h = bounds.height
        val margin = h * 0.05f
        val dist = (abs(detents) * h * DETENT_SCROLL).coerceAtMost(h * 0.7f)
        val up = detents > 0
        val x = position.x
        val from = if (up) position.y.coerceIn(dist + margin, h - margin) else position.y.coerceIn(margin, h - margin - dist)
        val to = if (up) from - dist else from + dist
        downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, Offset(x, from))
        for (i in 1..SWIPE_STEPS) {
            delay(FRAME_MS)
            inject(MotionEvent.ACTION_MOVE, Offset(x, from + (to - from) * i / SWIPE_STEPS))
        }
        // Rest at the end before lifting, so the page stops there rather than flinging on.
        repeat(3) {
            delay(SWIPE_REST_MS)
            inject(MotionEvent.ACTION_MOVE, Offset(x, to))
        }
        inject(MotionEvent.ACTION_UP, Offset(x, to))
    }

    /** One touch event at [at] (in the pane), sent into the car screen as the head unit's are. */
    private fun inject(action: Int, at: Offset) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = bounds.left + at.x; y = bounds.top + at.y; pressure = 1f; size = 1f
        })
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        // The view may already be detached when a pane goes away mid-touch.
        runCatching { view.dispatchTouchEvent(ev) }
        ev.recycle()
        touching = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
    }

    private companion object {
        const val FRAME_MS = 16L
        /** One tilt's first step, then the held speed, from slow (to aim) to fast (to cross the screen). */
        const val NUDGE_DP = 10f
        const val MIN_SPEED_DP = 220f
        const val MAX_SPEED_DP = 1700f
        const val RAMP_MS = 600f
        const val MAX_HOLD_MS = 8000L
        const val MIN_TAP_MS = 60L
        /** How much of the pane's height one knob detent scrolls, and how many add up at most. */
        const val DETENT_SCROLL = 0.2f
        const val MAX_DETENTS = 3
        const val SWIPE_STEPS = 6
        const val SWIPE_REST_MS = 30L
        const val SWIPE_GAP_MS = 60L
    }
}

/** A ring with a dot, light with a dark edge so it shows on any page; smaller while pressed. */
private fun DrawScope.drawPointer(at: Offset, pressed: Boolean) {
    if (!at.isSpecified) return
    val r = (if (pressed) 8.dp else 12.dp).toPx()
    drawCircle(Color.Black.copy(alpha = 0.7f), r + 2.dp.toPx(), at, style = Stroke(2.dp.toPx()))
    drawCircle(Color.White, r, at, style = Stroke(3.dp.toPx()))
    drawCircle(Color.Black.copy(alpha = 0.7f), 4.dp.toPx(), at)
    drawCircle(Color.White, 2.5.dp.toPx(), at)
}
