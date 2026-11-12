package net.prototux.aare.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbReattachTest {
    private val car = UsbReattach.Id("Android", "Android Auto", "Android Auto", "1.0", null)

    @Test fun sameAccessoryStillAttached() {
        assertTrue(UsbReattach.stillAttached(car) { listOf(car.copy()) })
    }

    @Test fun otherAccessoryOnly() {
        assertFalse(UsbReattach.stillAttached(car) { listOf(car.copy(model = "Something else")) })
    }

    @Test fun nothingAttached() {
        assertFalse(UsbReattach.stillAttached(car) { null })
        assertFalse(UsbReattach.stillAttached(car) { emptyList() })
    }

    /** The permission is gone once the accessory detached: reading it must not crash the app. */
    @Test fun lookupRefusedIsNotAttached() {
        assertFalse(UsbReattach.stillAttached(car) { throw SecurityException("User has not given permission to accessory") })
    }
}
