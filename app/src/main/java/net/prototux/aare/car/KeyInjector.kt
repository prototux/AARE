package net.prototux.aare.car

import android.view.KeyEvent

/**
 * One car key as AARE acts on it. [repeat] counts the downs of one hold, as on Android; [longPress]
 * marks a down that is a long press (the system long-press timeout passed, or the car flagged it),
 * and an up the car flagged as the end of one.
 */
internal data class CarKey(
    val code: Int,
    val down: Boolean,
    val metaState: Int = 0,
    val repeat: Int = 0,
    val longPress: Boolean = false,
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
 * synthetic one. Treating it as a plain click instead would, here, click
 * mid-hold and again on the up, and HOME would lose its long press.
 */
internal class KeyInjector(
    private val longPressTimeoutMs: Long,
    private val scheduler: Scheduler,
    private val emit: (CarKey) -> Unit,
) {
    interface Scheduler {
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    /** A key being held: fires its long press once, unless the up comes first. */
    private inner class Hold(private val first: CarKey) : Runnable {
        var repeats = 0
        var longFired = false
        override fun run() {
            longFired = true
            emit(first.copy(repeat = 1, longPress = true))
        }
    }

    private val held = HashMap<Int, Hold>()

    fun onKey(code: Int, down: Boolean, metaState: Int, carLongPress: Boolean) {
        val meta = metaStateFor(code, metaState)
        if (!down) {
            held.remove(code)?.let(scheduler::cancel)
            emit(CarKey(code, false, meta, longPress = carLongPress))
            return
        }
        val hold = held[code]
        if (carLongPress) {
            // The car reports the long press itself: one long press per hold, whoever sees it first.
            val h = hold ?: Hold(CarKey(code, true, meta)).also { held[code] = it }
            scheduler.cancel(h)
            emit(CarKey(code, true, meta, repeat = ++h.repeats, longPress = !h.longFired))
            h.longFired = true
            return
        }
        if (hold == null) {
            val first = CarKey(code, true, meta)
            held[code] = Hold(first).also { scheduler.postDelayed(it, longPressTimeoutMs) }
            emit(first)
        } else {
            emit(CarKey(code, true, meta, repeat = ++hold.repeats))
        }
    }

    /** Ends every hold (the session ended): no long press fires afterwards. */
    fun clear() {
        held.values.forEach(scheduler::cancel)
        held.clear()
    }

    companion object {
        /** The rotary controller's protocol keycode. */
        const val KEYCODE_ROTARY_CONTROLLER = 65536

        private val NO_META = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KEYCODE_ROTARY_CONTROLLER,
        )

        /** The metastate a car key goes on with: cleared on navigation keys. */
        fun metaStateFor(code: Int, metaState: Int) = if (code in NO_META) 0 else metaState
    }
}
