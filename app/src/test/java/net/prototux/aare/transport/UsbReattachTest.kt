package net.prototux.aare.transport

import net.prototux.aare.transport.UsbReattach.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbReattachTest {
    private fun next(present: Boolean, permitted: Boolean, elapsedMs: Long) = UsbReattach.next(present, permitted, elapsedMs)

    /** A link that dropped without a USB reset: the accessory and its permission are still there. */
    @Test fun stillThereWithThePermissionOpensAtOnce() {
        assertEquals(Step.OPEN, next(present = true, permitted = true, elapsedMs = UsbReattach.FIRST_LOOK_MS))
    }

    /** #182: right after a reset the old accessory is gone or has lost its permission; don't ask yet. */
    @Test fun backWithoutThePermissionWaitsForAndroidToGrantIt() {
        assertEquals(Step.WAIT, next(present = false, permitted = false, elapsedMs = 2_000))
        assertEquals(Step.WAIT, next(present = true, permitted = false, elapsedMs = 2_000))
        assertEquals(Step.OPEN, next(present = true, permitted = true, elapsedMs = 3_000))
    }

    /** Phones that never grant a re-attached accessory (Samsung) still get the dialog, later. */
    @Test fun stillWithoutThePermissionAsks() {
        assertEquals(Step.ASK, next(present = true, permitted = false, elapsedMs = UsbReattach.ASK_AFTER_MS))
        assertEquals(Step.ASK, next(present = true, permitted = false, elapsedMs = UsbReattach.GIVE_UP_MS + 1))
    }

    /** Unplugged: it never comes back. */
    @Test fun neverBackGivesUp() {
        assertEquals(Step.WAIT, next(present = false, permitted = false, elapsedMs = UsbReattach.GIVE_UP_MS - 1))
        assertEquals(Step.GIVE_UP, next(present = false, permitted = false, elapsedMs = UsbReattach.GIVE_UP_MS))
    }
}
