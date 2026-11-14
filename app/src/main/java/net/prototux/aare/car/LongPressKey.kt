package net.prototux.aare.car

/**
 * A car key with a press and a long-press action (HOME: the dashboard, or back to the car's own UI).
 * Head units report a long press as a repeated down with longpress set and then an up, which must not
 * also count as a press, or only as an up with longpress set. [KeyInjector] adds its own long-press
 * down when a key is held past the system timeout. Main thread.
 */
internal class LongPressKey(private val onPress: () -> Unit, private val onLongPress: () -> Unit) {
    /** The long press already fired on a down: its up and further repeats do nothing. */
    private var longFired = false

    /** [repeat] is the down's repeat count: only a fresh down (0) starts a new press. */
    fun onKey(down: Boolean, longPress: Boolean, repeat: Int = 0) {
        if (down) {
            when {
                !longPress -> if (repeat == 0) longFired = false
                !longFired -> { longFired = true; onLongPress() }
            }
            return
        }
        when {
            longFired -> longFired = false
            longPress -> onLongPress()
            else -> onPress()
        }
    }
}
