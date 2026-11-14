package net.prototux.aare.car

import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCheckTest {
    @Test
    fun waitsForBothHalves() {
        assertNull(PairingCheck.decide(null, Int.MIN_VALUE, "123456"))
        assertNull(PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 123456, null))
        assertNull(PairingCheck.decide(PairingCheck.VARIANT_PIN, Int.MIN_VALUE, null))
    }

    @Test
    fun pinIsEnteredForTheCar() {
        assertEquals(PairingCheck.Decision("0000", MessageStatus.STATUS_SUCCESS),
            PairingCheck.decide(PairingCheck.VARIANT_PIN, Int.MIN_VALUE, "0000"))
    }

    @Test
    fun matchingPasskeyIsZeroPadded() {
        assertEquals(PairingCheck.Decision(null, MessageStatus.STATUS_SUCCESS),
            PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 1234, "001234"))
    }

    @Test
    fun differentPasskeyIsAMismatch() {
        assertEquals(PairingCheck.Decision(null, MessageStatus.STATUS_BLUETOOTH_AUTH_DATA_MISMATCH),
            PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 1234, "1234"))
    }

    @Test
    fun otherVariantsAreAnInvalidMethod() {
        // Consent (3) needs no auth data from the car; passkey entry (1) does.
        assertEquals(PairingCheck.Decision(null, MessageStatus.STATUS_BLUETOOTH_INVALID_PAIRING_METHOD),
            PairingCheck.decide(3, Int.MIN_VALUE, null))
        assertNull(PairingCheck.decide(1, Int.MIN_VALUE, null))
        assertEquals(PairingCheck.Decision(null, MessageStatus.STATUS_BLUETOOTH_INVALID_PAIRING_METHOD),
            PairingCheck.decide(1, Int.MIN_VALUE, "123456"))
    }
}
