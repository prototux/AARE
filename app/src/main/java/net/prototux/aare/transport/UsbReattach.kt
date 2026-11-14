package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory

/**
 * Finding the head unit's USB accessory again after a session ended, to reconnect to it.
 *
 * `UsbAccessory.equals` compares serials, and `getSerial()` needs the USB permission, which
 * Android has already taken back once the accessory went away: `accessoryList.contains(acc)`
 * then threw a SecurityException and crashed the app at the end of every USB session (#152).
 * One phone has one accessory port, so the other fields are enough to tell it is the same one.
 */
internal object UsbReattach {
    data class Id(val manufacturer: String?, val model: String?, val description: String?, val version: String?, val uri: String?)

    fun id(a: UsbAccessory) = Id(a.manufacturer, a.model, a.description, a.version, a.uri)

    /** The same head unit, without `UsbAccessory.equals` (it reads the serial, which can throw). */
    fun same(a: UsbAccessory?, b: UsbAccessory?): Boolean = a != null && b != null && id(a) == id(b)

    /** What to do while waiting for a head unit to come back after its USB link dropped. */
    enum class Step { WAIT, OPEN, ASK, GIVE_UP }

    /**
     * A head unit that resets its USB comes back as a new accessory, which Android grants AARE by
     * itself when it's the default app for it. Opening the old one instead failed on its revoked
     * permission and showed the permission dialog, mid-drive (#182). So: open it once it's back
     * with the permission, ask only if it's back without it for a while, give up if it never is.
     */
    fun next(present: Boolean, permitted: Boolean, elapsedMs: Long): Step = when {
        present && permitted -> Step.OPEN
        present && elapsedMs >= ASK_AFTER_MS -> Step.ASK
        elapsedMs >= GIVE_UP_MS -> Step.GIVE_UP
        else -> Step.WAIT
    }

    /** First look: the link may come back at once, but a resetting head unit needs a moment. */
    const val FIRST_LOOK_MS = 1_500L
    const val LOOK_EVERY_MS = 500L
    /** Long enough for Android to grant a re-attached accessory (Samsung phones never do). */
    const val ASK_AFTER_MS = 5_000L
    const val GIVE_UP_MS = 10_000L
}
