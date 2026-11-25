package net.prototux.aare.car

import android.view.KeyEvent

/**
 * One car key as AARE acts on it. [repeat] counts the downs of one hold, as on Android; [longPress]
 * marks a down that is a long press (the system long-press timeout passed, or the car flagged it),
 * and an up the car flagged as the end of one. [downTime] is when the hold's first down came: the
 * repeats and the up carry it too, as Android's own key events do.
 */
internal data class CarKey(
    val code: Int,
    val down: Boolean,
    val metaState: Int = 0,
    val repeat: Int = 0,
    val longPress: Boolean = false,
    val downTime: Long = 0,
)

/**
 * Turns the car's key reports into [CarKey]s, injected on the main thread:
 * - the car's metastate goes through, except on the D-pad and the rotary controller, which get none;
 * - a key held for [longPressTimeoutMs] (the system long-press timeout) gets a second down marked as
 *   a long press, with repeat count 1, like a held hardware key on Android: a car that never sets
 *   the longpress flag still has long presses;
 * - downs the car repeats during a hold count up the repeat count;
 * - the up ends the hold and cancels a long press still to come.
 * A down the car flags as a long press itself goes through as one (once per hold) and cancels the
 * synthetic one. Treating it as a plain click instead would click mid-hold and again on the up.
 *
 * A hold the car opens with its long-press flag (no plain down before it) may never get an up: a
 * Mazda CMU8 sends its held voice button that way (#423), and every action waits for the up. Such
 * a hold ends by itself once [LONG_ONLY_END_MS] pass with no repeat; the car's up, if it comes
 * later, is dropped.
 *
 * The focus keys ([FOCUS_KEYS]: the D-pad arrows, the rotary controller, the navigation keys) get
 * no synthetic long press: held, they step the focus with the car's repeats, and a long press on
 * top would act on whatever the focus landed on. The car's own longpress flag still goes through.
 *
 * Keys in [carLongPressOnly] (HOME) get no hold: they go through as the car sends them, with its own
 * longpress flag. A car whose key-up comes late would otherwise turn a short HOME press into a long
 * one, which leaves the projection.
 */
internal class KeyInjector(
    private val longPressTimeoutMs: Long,
    private val scheduler: Scheduler,
    private val carLongPressOnly: Set<Int> = emptySet(),
    private val emit: (CarKey) -> Unit,
) {
    interface Scheduler {
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
        /** The clock the key events' times come from (the uptime on Android). */
        fun uptimeMillis(): Long
    }

    /** A key being held: fires its long press once, unless the up comes first. */
    private inner class Hold(val first: CarKey) : Runnable {
        var repeats = 0
        var longFired = false
        override fun run() {
            longFired = true
            emit(first.copy(repeat = 1, longPress = true))
        }
    }

    private val held = HashMap<Int, Hold>()

    /** A hold the car opened with its long-press flag: ends by itself when its repeats stop. */
    private inner class LongOnlyHold(val first: CarKey) : Runnable {
        var repeats = 1
        override fun run() {
            if (longOnly.remove(first.code) !== this) return
            endedEarly += first.code
            emit(CarKey(first.code, false, first.metaState, longPress = true, downTime = first.downTime))
        }
    }

    private val longOnly = HashMap<Int, LongOnlyHold>()

    /** Keys whose [LongOnlyHold] ended by itself: the car's up, if it still comes, is dropped. */
    private val endedEarly = HashSet<Int>()

    /** When each [carLongPressOnly] key still down went down. */
    private val passedDown = HashMap<Int, Long>()

    fun onKey(code: Int, down: Boolean, metaState: Int, carLongPress: Boolean) {
        val meta = metaStateFor(code, metaState)
        val now = scheduler.uptimeMillis()
        if (code in carLongPressOnly) {
            val downTime = if (down) passedDown.getOrPut(code) { now } else passedDown.remove(code) ?: now
            emit(CarKey(code, down, meta, longPress = carLongPress, downTime = downTime))
            return
        }
        if (!down) {
            longOnly.remove(code)?.let { lo ->
                scheduler.cancel(lo)
                emit(CarKey(code, false, meta, longPress = carLongPress, downTime = lo.first.downTime))
                return
            }
            if (endedEarly.remove(code)) return
            val hold = held.remove(code)?.also(scheduler::cancel)
            emit(CarKey(code, false, meta, longPress = carLongPress, downTime = hold?.first?.downTime ?: now))
            return
        }
        endedEarly -= code
        longOnly[code]?.let { lo ->
            // The car repeating a hold it opened as a long press: the hold goes on.
            scheduler.cancel(lo)
            scheduler.postDelayed(lo, LONG_ONLY_END_MS)
            emit(CarKey(code, true, meta, repeat = ++lo.repeats, downTime = lo.first.downTime))
            return
        }
        val hold = held[code]
        if (carLongPress && hold == null) {
            val first = CarKey(code, true, meta, downTime = now)
            longOnly[code] = LongOnlyHold(first).also { scheduler.postDelayed(it, LONG_ONLY_END_MS) }
            emit(first.copy(repeat = 1, longPress = true))
            return
        }
        if (carLongPress && hold != null) {
            // The car reports the long press itself: one long press per hold, whoever sees it first.
            val h = hold
            scheduler.cancel(h)
            emit(CarKey(code, true, meta, repeat = ++h.repeats, longPress = !h.longFired, downTime = h.first.downTime))
            h.longFired = true
            return
        }
        if (hold == null) {
            val first = CarKey(code, true, meta, downTime = now)
            held[code] = Hold(first).also { if (code !in FOCUS_KEYS) scheduler.postDelayed(it, longPressTimeoutMs) }
            emit(first)
        } else {
            emit(CarKey(code, true, meta, repeat = ++hold.repeats, downTime = hold.first.downTime))
        }
    }

    /** Ends every hold (the session ended): no long press fires afterwards. */
    fun clear() {
        held.values.forEach(scheduler::cancel)
        held.clear()
        longOnly.values.forEach(scheduler::cancel)
        longOnly.clear()
        endedEarly.clear()
        passedDown.clear()
    }

    companion object {
        /** The rotary controller's protocol keycode. */
        const val KEYCODE_ROTARY_CONTROLLER = 65536

        /**
         * How long a hold the car opened with its long-press flag waits for a repeat or the up before
         * ending by itself. Long enough for a car's repeats (a few per second), short enough that
         * the voice button doesn't feel dead.
         */
        const val LONG_ONLY_END_MS = 1_000L

        private val NO_META = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KEYCODE_ROTARY_CONTROLLER,
        )

        /** Keys that move the focus: no synthetic long press while they are held. */
        val FOCUS_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KEYCODE_ROTARY_CONTROLLER,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_NAVIGATE_IN, KeyEvent.KEYCODE_NAVIGATE_OUT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,
        )

        /** The metastate a car key goes on with: cleared on navigation keys. */
        fun metaStateFor(code: Int, metaState: Int) = if (code in NO_META) 0 else metaState
    }
}
