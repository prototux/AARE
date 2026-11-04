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
