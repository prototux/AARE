package net.prototux.aare.car

/**
 * A car key with a press and a long-press action (HOME: the dashboard, or back to the car's own UI).
 * Head units report a long press as a repeated down with longpress set and then an up, which must not
 * also count as a press, or only as an up with longpress set. Main thread.
 */
internal class LongPressKey(private val onPress: () -> Unit, private val onLongPress: () -> Unit) {
    /** The long press already fired on a down: its up and further repeats do nothing. */
    private var longFired = false

    fun onKey(down: Boolean, longPress: Boolean) {
        if (down) {
            if (!longPress) longFired = false
            else if (!longFired) { longFired = true; onLongPress() }
            return
        }
        when {
            longFired -> longFired = false
            longPress -> onLongPress()
            else -> onPress()
        }
    }
}

/**
 * A car key that acts once per press, as early as it can: on its first down, or on its up when the
 * head unit sent no down (some report a held key only as it is let go). Repeated downs while it is
 * held do nothing. Main thread.
 */
internal class PressOnce {
    private var acted = false

    /** Whether this event is the one to act on. */
    fun pressed(down: Boolean): Boolean {
        if (down) return !acted.also { acted = true }
        return !acted.also { acted = false }
    }
}
